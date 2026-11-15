package com.groupcollections.refund;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.contribution.ContributionRepository;
import com.groupcollections.goal.GoalRepository;
import com.groupcollections.operator.OperatorPrincipal;
import com.groupcollections.operator.OperatorRepository;
import com.groupcollections.operator.OperatorRole;
import com.groupcollections.payout.PayoutRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Operator-authenticated pre-payout refund request and provider verification. */
@Service
@ConditionalOnProperty(name = "collections.refunds.enabled", havingValue = "true")
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);
  private static final String SOURCE_GOAL_POOL = "GOAL_POOL";

  private final RefundRepository refunds;
  private final ContributionRepository contributions;
  private final GoalRepository goals;
  private final PayoutRepository payouts;
  private final OperatorRepository operators;
  private final RefundProvider provider;
  private final RefundCompletionService completion;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;
  private final Clock clock;

  public RefundService(
      RefundRepository refunds,
      ContributionRepository contributions,
      GoalRepository goals,
      PayoutRepository payouts,
      OperatorRepository operators,
      RefundProvider provider,
      RefundCompletionService completion,
      AlertPublisher alerts,
      MeterRegistry meters,
      Clock clock) {
    this.refunds = refunds;
    this.contributions = contributions;
    this.goals = goals;
    this.payouts = payouts;
    this.operators = operators;
    this.provider = provider;
    this.completion = completion;
    this.alerts = alerts;
    this.meters = meters;
    this.clock = clock;
  }

  @Transactional
  public RefundView request(
      OperatorPrincipal actor, UUID contributionId, String idempotencyKey, String reason) {
    actor.requireRole(OperatorRole.REFUND_OPERATOR);
    String key = requireKey(idempotencyKey);
    String cleanReason = requireReason(reason);
    Instant now = Instant.now(clock);

    Optional<Refund> existing = refunds.findByContributionAndIdempotencyKey(contributionId, key);
    if (existing.isPresent()) {
      return replay(existing.get(), cleanReason);
    }

    ContributionRepository.RefundCandidate candidate =
        contributions
            .findRefundCandidate(contributionId)
            .orElseThrow(RefundException::contributionNotFound);
    if (!goals.lockById(candidate.goalId())) {
      throw RefundException.contributionNotFound();
    }
    candidate =
        contributions
            .findRefundCandidateForUpdate(contributionId)
            .orElseThrow(RefundException::contributionNotFound);

    existing = refunds.findByContributionAndIdempotencyKey(contributionId, key);
    if (existing.isPresent()) {
      return replay(existing.get(), cleanReason);
    }
    if (candidate.state() != com.groupcollections.contribution.ContributionState.CONFIRMED) {
      throw RefundException.notEligible();
    }
    if (candidate.requiresReconciliation()) {
      throw RefundException.suspenseRequiresReconciliation();
    }
    if (candidate.providerReference() == null || candidate.providerReference().isBlank()) {
      throw RefundException.notEligible();
    }
    if (refunds.findActiveForContribution(contributionId).isPresent()) {
      throw RefundException.inProgress();
    }
    Optional<PayoutRepository.PayoutBlock> block = payouts.findBlockingForGoal(candidate.goalId());
    if (block.isPresent()) {
      PayoutRepository.PayoutBlock payout = block.get();
      if ("COMPLETED".equals(payout.state())) {
        throw RefundException.afterPayout();
      }
      throw RefundException.payoutInFlight(payout.id().toString(), payout.state());
    }

    UUID refundId = UUID.randomUUID();
    try {
      refunds.insert(
          refundId,
          contributionId,
          candidate.goalId(),
          SOURCE_GOAL_POOL,
          candidate.amountMinorUnits(),
          candidate.platformFeeMinorUnits(),
          candidate.processingFeeMinorUnits(),
          candidate.totalMinorUnits(),
          candidate.currencyCode(),
          key,
          candidate.providerReference(),
          actor.id(),
          cleanReason,
          now);
    } catch (DuplicateKeyException failure) {
      Optional<Refund> raced = refunds.findByContributionAndIdempotencyKey(contributionId, key);
      if (raced.isPresent()) {
        return replay(raced.get(), cleanReason);
      }
      throw RefundException.inProgress();
    }
    refunds.appendEvent(refundId, "REQUESTED", null, RefundState.PENDING.name(), null, now);
    operators.recordAction(
        actor.id(),
        "REFUND_REQUESTED",
        "CONTRIBUTION",
        contributionId,
        cleanReason,
        null,
        RefundState.PENDING.name(),
        now);
    meters.counter("refunds.requested").increment();

    Refund refund = refunds.findById(refundId).orElseThrow(RefundException::notFound);
    afterCommit(() -> callProvider(refund));
    return RefundView.from(refund);
  }

  public RefundView get(OperatorPrincipal actor, UUID refundId) {
    actor.requireRole(OperatorRole.REFUND_OPERATOR);
    return refunds.findById(refundId).map(RefundView::from).orElseThrow(RefundException::notFound);
  }

  public List<RefundView> list(OperatorPrincipal actor, String state) {
    actor.requireRole(OperatorRole.REFUND_OPERATOR);
    RefundState filter = null;
    if (state != null && !state.isBlank()) {
      try {
        filter = RefundState.valueOf(state);
      } catch (IllegalArgumentException failure) {
        throw RefundException.invalid("Unknown refund state");
      }
    }
    return refunds.findRecent(filter, 50).stream().map(RefundView::from).toList();
  }

  public RefundView verify(OperatorPrincipal actor, UUID refundId, String reason) {
    actor.requireRole(OperatorRole.REFUND_OPERATOR);
    String cleanReason = requireReason(reason);
    Refund refund = refunds.findById(refundId).orElseThrow(RefundException::notFound);
    if (refund.state() != RefundState.PROVIDER_PENDING) {
      throw RefundException.notPending();
    }
    RefundProvider.RefundResult result = provider.verify(refund);
    completion.process(refundId, result);
    operators.recordAction(
        actor.id(),
        "REFUND_VERIFIED",
        "REFUND",
        refundId,
        cleanReason,
        RefundState.PROVIDER_PENDING.name(),
        result.status().name(),
        Instant.now(clock));
    meters.counter("refunds.verified").increment();
    return refunds.findById(refundId).map(RefundView::from).orElseThrow(RefundException::notFound);
  }

  void callProvider(Refund refund) {
    RefundProvider.RefundResult result;
    try {
      result = provider.refund(refund);
    } catch (RuntimeException failure) {
      result =
          RefundProvider.RefundResult.pending(
              null, RefundProvider.RefundResult.PendingReason.UNKNOWN_STATUS);
    }
    try {
      completion.process(refund.id(), result);
    } catch (RuntimeException failure) {
      log.error(
          "event=refund_completion_failed refundId={} contributionId={}",
          refund.id(),
          refund.contributionId(),
          failure);
      alerts.publish(
          new Alert(
              Alert.AlertType.REFUND_COMPLETION_FAILED,
              refund.id().toString(),
              "provider result could not be applied"));
    }
  }

  private static RefundView replay(Refund existing, String reason) {
    if (!existing.reason().equals(reason)) {
      throw RefundException.idempotencyConflict();
    }
    return RefundView.from(existing);
  }

  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw RefundException.invalid("Idempotency-Key is required");
    }
    return key.trim();
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw RefundException.invalid("A reason is required");
    }
    String cleaned = reason.trim();
    if (RefundReasons.containsPhone(cleaned)) {
      throw RefundException.invalid("A reason must not contain a phone number");
    }
    return cleaned;
  }

  private void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
