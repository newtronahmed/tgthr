package com.groupcollections.refund;

import com.groupcollections.contribution.ContributionRepository;
import com.groupcollections.contribution.ContributionState;
import com.groupcollections.ledger.LedgerAccount;
import com.groupcollections.ledger.LedgerDirection;
import com.groupcollections.ledger.LedgerEntry;
import com.groupcollections.ledger.LedgerEntryType;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.Money;
import com.groupcollections.ledger.PostingSet;
import com.groupcollections.operator.OperatorAuthService;
import com.groupcollections.operator.OperatorPrincipal;
import com.groupcollections.operator.OperatorRepository;
import com.groupcollections.operator.OperatorRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Suspense queue reads and the reclassify/refund/income resolutions
 * (AC-OPS-001/002/003/004/006/009).
 */
@Service
@ConditionalOnProperty(name = "collections.refunds.enabled", havingValue = "true")
public class ReconciliationService {

  private static final String SOURCE_SUSPENSE = "SUSPENSE";

  private final ContributionRepository contributions;
  private final ReconciliationRepository resolutions;
  private final RefundRepository refunds;
  private final RefundService refundService;
  private final LedgerRepository ledger;
  private final OperatorRepository operators;
  private final OperatorAuthService auth;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Duration suspenseIncomeAfter;

  public ReconciliationService(
      ContributionRepository contributions,
      ReconciliationRepository resolutions,
      RefundRepository refunds,
      RefundService refundService,
      LedgerRepository ledger,
      OperatorRepository operators,
      OperatorAuthService auth,
      MeterRegistry meters,
      Clock clock,
      @Value("${collections.refunds.suspense-income-after:P30D}") Duration suspenseIncomeAfter) {
    this.contributions = contributions;
    this.resolutions = resolutions;
    this.refunds = refunds;
    this.refundService = refundService;
    this.ledger = ledger;
    this.operators = operators;
    this.auth = auth;
    this.meters = meters;
    this.clock = clock;
    this.suspenseIncomeAfter = suspenseIncomeAfter;
  }

  public List<ReconciliationItemView> pending(OperatorPrincipal operator) {
    operator.requireAnyRole(OperatorRole.REFUND_OPERATOR, OperatorRole.RECONCILER);
    Instant now = Instant.now(clock);
    return contributions.findRequiringReconciliation().stream()
        .map(
            item ->
                new ReconciliationItemView(
                    item.id(),
                    item.goalId(),
                    item.goalTitle(),
                    item.amount().minorUnits(),
                    item.amount().currencyCode(),
                    item.providerReference(),
                    item.updatedAt(),
                    Math.max(0, Duration.between(item.updatedAt(), now).toSeconds())))
        .toList();
  }

  @Transactional
  public ReconciliationResolutionView reclassify(
      OperatorPrincipal actor, UUID contributionId, String idempotencyKey, String reason) {
    actor.requireRole(OperatorRole.RECONCILER);
    String key = requireKey(idempotencyKey);
    String cleanReason = requireReason(reason);
    Instant now = Instant.now(clock);

    ContributionRepository.RefundCandidate candidate =
        contributions
            .findRefundCandidateForUpdate(contributionId)
            .orElseThrow(RefundException::contributionNotFound);

    Optional<ReconciliationResolution> existing =
        resolutions.findByContributionForUpdate(contributionId);
    if (existing.isPresent()) {
      return replay(existing.get(), key, cleanReason, "RECLASSIFY");
    }
    if (candidate.state() != ContributionState.CONFIRMED) {
      throw RefundException.notEligible();
    }
    if (!candidate.requiresReconciliation()) {
      throw RefundException.notSuspense();
    }
    if (refunds.findActiveForContribution(contributionId).isPresent()) {
      throw RefundException.inProgress();
    }

    Money amount = Money.of(candidate.amountMinorUnits(), candidate.currencyCode());
    UUID postingSetId =
        UUID.nameUUIDFromBytes(("reclassify-" + contributionId).getBytes(StandardCharsets.UTF_8));
    PostingSet posting =
        new PostingSet(
            postingSetId,
            List.of(
                new LedgerEntry(
                    LedgerAccount.SUSPENSE,
                    LedgerEntryType.CONTRIBUTION,
                    LedgerDirection.DEBIT,
                    amount),
                new LedgerEntry(
                    LedgerAccount.GOAL_POOL,
                    LedgerEntryType.CONTRIBUTION,
                    LedgerDirection.CREDIT,
                    amount)));
    ledger.post(contributionId, posting);
    contributions.clearRequiresReconciliation(contributionId, now);

    UUID resolutionId = UUID.randomUUID();
    resolutions.insert(
        resolutionId,
        contributionId,
        "RECLASSIFY",
        "APPLIED",
        actor.id(),
        null,
        cleanReason,
        key,
        null,
        postingSetId,
        now,
        now);
    operators.recordAction(
        actor.id(),
        "RECONCILIATION_RECLASSIFIED",
        "CONTRIBUTION",
        contributionId,
        cleanReason,
        "SUSPENSE",
        "GOAL_POOL",
        now);
    meters.counter("reconciliations.reclassified").increment();

    ReconciliationResolution saved =
        resolutions.findByContribution(contributionId).orElseThrow(RefundException::notFound);
    return ReconciliationResolutionView.from(saved);
  }

  @Transactional
  public RefundView refund(
      OperatorPrincipal actor, UUID contributionId, String idempotencyKey, String reason) {
    actor.requireRole(OperatorRole.RECONCILER);
    String key = requireKey(idempotencyKey);
    String cleanReason = requireReason(reason);
    Instant now = Instant.now(clock);

    Optional<Refund> existing = refunds.findByContributionAndIdempotencyKey(contributionId, key);
    if (existing.isPresent()) {
      return replayRefund(existing.get(), cleanReason);
    }

    ContributionRepository.RefundCandidate candidate =
        contributions
            .findRefundCandidateForUpdate(contributionId)
            .orElseThrow(RefundException::contributionNotFound);

    Optional<ReconciliationResolution> resolution =
        resolutions.findByContributionForUpdate(contributionId);
    if (resolution.isPresent()) {
      throw RefundException.alreadyResolved();
    }
    if (candidate.state() != ContributionState.CONFIRMED) {
      throw RefundException.notEligible();
    }
    if (!candidate.requiresReconciliation()) {
      throw RefundException.notSuspense();
    }
    if (candidate.providerReference() == null || candidate.providerReference().isBlank()) {
      throw RefundException.notEligible();
    }
    if (refunds.findActiveForContribution(contributionId).isPresent()) {
      throw RefundException.inProgress();
    }

    UUID refundId = UUID.randomUUID();
    refunds.insert(
        refundId,
        contributionId,
        candidate.goalId(),
        SOURCE_SUSPENSE,
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
    refunds.appendEvent(refundId, "REQUESTED", null, RefundState.PENDING.name(), null, now);
    operators.recordAction(
        actor.id(),
        "RECONCILIATION_REFUND_REQUESTED",
        "CONTRIBUTION",
        contributionId,
        cleanReason,
        "SUSPENSE",
        RefundState.PENDING.name(),
        now);
    meters.counter("reconciliations.refund_requested").increment();

    Refund refund = refunds.findById(refundId).orElseThrow(RefundException::notFound);
    afterCommit(() -> refundService.callProvider(refund));
    return RefundView.from(refund);
  }

  @Transactional
  public ReconciliationResolutionView proposeIncome(
      OperatorPrincipal actor, UUID contributionId, String idempotencyKey, String reason) {
    actor.requireRole(OperatorRole.RECONCILER);
    String key = requireKey(idempotencyKey);
    String cleanReason = requireReason(reason);
    Instant now = Instant.now(clock);

    ContributionRepository.RefundCandidate candidate =
        contributions
            .findRefundCandidateForUpdate(contributionId)
            .orElseThrow(RefundException::contributionNotFound);

    Optional<ReconciliationResolution> existing =
        resolutions.findByContributionForUpdate(contributionId);
    if (existing.isPresent()) {
      if ("INCOME".equals(existing.get().resolution())
          && "PROPOSED".equals(existing.get().status())
          && existing.get().idempotencyKey().equals(key)
          && existing.get().reason().equals(cleanReason)) {
        return ReconciliationResolutionView.from(existing.get());
      }
      throw RefundException.alreadyResolved();
    }
    requireSuspenseItem(candidate);
    if (refunds.findActiveForContribution(contributionId).isPresent()) {
      throw RefundException.inProgress();
    }
    requireAged(candidate, now);

    UUID resolutionId = UUID.randomUUID();
    resolutions.insert(
        resolutionId,
        contributionId,
        "INCOME",
        "PROPOSED",
        actor.id(),
        null,
        cleanReason,
        key,
        null,
        null,
        now,
        null);
    operators.recordAction(
        actor.id(),
        "RECONCILIATION_INCOME_PROPOSED",
        "CONTRIBUTION",
        contributionId,
        cleanReason,
        "SUSPENSE",
        "PROPOSED",
        now);
    meters.counter("reconciliations.income_proposed").increment();

    ReconciliationResolution saved =
        resolutions.findByContribution(contributionId).orElseThrow(RefundException::notFound);
    return ReconciliationResolutionView.from(saved);
  }

  @Transactional
  public ReconciliationResolutionView approveIncome(
      OperatorPrincipal actor,
      UUID contributionId,
      String idempotencyKey,
      String reason,
      String totpCode,
      String recoveryCode) {
    actor.requireRole(OperatorRole.FINANCE_APPROVER);
    String key = requireKey(idempotencyKey);
    String cleanReason = requireReason(reason);
    auth.requireStepUp(actor, totpCode, recoveryCode);
    Instant now = Instant.now(clock);

    ContributionRepository.RefundCandidate candidate =
        contributions
            .findRefundCandidateForUpdate(contributionId)
            .orElseThrow(RefundException::contributionNotFound);

    ReconciliationResolution resolution =
        resolutions
            .findByContributionForUpdate(contributionId)
            .orElseThrow(RefundException::notProposed);
    if ("APPLIED".equals(resolution.status())) {
      if ("INCOME".equals(resolution.resolution())
          && key.equals(resolution.approvalIdempotencyKey())) {
        return ReconciliationResolutionView.from(resolution);
      }
      throw RefundException.alreadyResolved();
    }
    if (!"INCOME".equals(resolution.resolution()) || !"PROPOSED".equals(resolution.status())) {
      throw RefundException.alreadyResolved();
    }
    if (resolution.proposerId().equals(actor.id())) {
      throw RefundException.dualControlRequired();
    }
    requireSuspenseItem(candidate);
    requireAged(candidate, now);

    Money amount = Money.of(candidate.amountMinorUnits(), candidate.currencyCode());
    UUID postingSetId =
        UUID.nameUUIDFromBytes(("income-" + contributionId).getBytes(StandardCharsets.UTF_8));
    PostingSet posting =
        new PostingSet(
            postingSetId,
            List.of(
                new LedgerEntry(
                    LedgerAccount.SUSPENSE,
                    LedgerEntryType.ADJUSTMENT,
                    LedgerDirection.DEBIT,
                    amount),
                new LedgerEntry(
                    LedgerAccount.PLATFORM_REVENUE,
                    LedgerEntryType.ADJUSTMENT,
                    LedgerDirection.CREDIT,
                    amount)));
    ledger.post(contributionId, posting);
    if (resolutions.markApproved(resolution.id(), actor.id(), postingSetId, key, now) == 0) {
      throw RefundException.alreadyResolved();
    }
    operators.recordAction(
        actor.id(),
        "RECONCILIATION_INCOME_APPROVED",
        "CONTRIBUTION",
        contributionId,
        cleanReason,
        "PROPOSED",
        "APPLIED",
        now);
    meters.counter("reconciliations.income_approved").increment();

    ReconciliationResolution saved =
        resolutions.findByContribution(contributionId).orElseThrow(RefundException::notFound);
    return ReconciliationResolutionView.from(saved);
  }

  private static void requireSuspenseItem(ContributionRepository.RefundCandidate candidate) {
    if (candidate.state() != ContributionState.CONFIRMED) {
      throw RefundException.notEligible();
    }
    if (!candidate.requiresReconciliation()) {
      throw RefundException.notSuspense();
    }
  }

  private void requireAged(ContributionRepository.RefundCandidate candidate, Instant now) {
    if (candidate.updatedAt().plus(suspenseIncomeAfter).isAfter(now)) {
      throw RefundException.notAged();
    }
  }

  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw RefundException.invalidReconciliation("Idempotency-Key is required");
    }
    return key.trim();
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw RefundException.invalidReconciliation("A reason is required");
    }
    return reason.trim();
  }

  private static RefundView replayRefund(Refund existing, String reason) {
    if (!existing.reason().equals(reason)) {
      throw RefundException.idempotencyConflict();
    }
    return RefundView.from(existing);
  }

  private static ReconciliationResolutionView replay(
      ReconciliationResolution existing, String key, String reason, String resolution) {
    if (!existing.resolution().equals(resolution)
        || !existing.idempotencyKey().equals(key)
        || !existing.reason().equals(reason)) {
      throw RefundException.alreadyResolved();
    }
    return ReconciliationResolutionView.from(existing);
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
