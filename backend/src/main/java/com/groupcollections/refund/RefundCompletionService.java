package com.groupcollections.refund;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.contribution.ContributionEvent;
import com.groupcollections.contribution.ContributionRepository;
import com.groupcollections.ledger.LedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies one provider result to the refund, ledger and contribution in one transaction. */
@Service
@ConditionalOnProperty(name = "collections.refunds.enabled", havingValue = "true")
public class RefundCompletionService {

  private static final Logger log = LoggerFactory.getLogger(RefundCompletionService.class);

  private final RefundRepository refunds;
  private final ContributionRepository contributions;
  private final LedgerRepository ledger;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;
  private final Clock clock;
  private final boolean feeRetained;

  public RefundCompletionService(
      RefundRepository refunds,
      ContributionRepository contributions,
      LedgerRepository ledger,
      AlertPublisher alerts,
      MeterRegistry meters,
      Clock clock,
      @Value("${collections.refunds.partner-retains-processing-fee:true}") boolean feeRetained) {
    this.refunds = refunds;
    this.contributions = contributions;
    this.ledger = ledger;
    this.alerts = alerts;
    this.meters = meters;
    this.clock = clock;
    this.feeRetained = feeRetained;
  }

  @Transactional
  public void process(java.util.UUID refundId, RefundProvider.RefundResult result) {
    Instant now = Instant.now(clock);
    Refund refund = refunds.findByIdForUpdate(refundId).orElseThrow(RefundException::notFound);
    if (refund.state() == RefundState.SUCCEEDED || refund.state() == RefundState.FAILED) {
      return;
    }
    switch (result.status()) {
      case SUCCESS -> succeed(refund, result, now);
      case FAILED -> fail(refund, result, now);
      case PENDING -> pending(refund, result, now);
    }
  }

  private void succeed(Refund refund, RefundProvider.RefundResult result, Instant now) {
    ContributionRepository.RefundCandidate candidate =
        contributions
            .findRefundCandidateForUpdate(refund.contributionId())
            .orElseThrow(RefundException::contributionNotFound);
    if (candidate.state() != com.groupcollections.contribution.ContributionState.CONFIRMED) {
      refunds.appendEvent(
          refund.id(),
          "SUCCESS_NOT_CONFIRMED",
          refund.state().name(),
          RefundState.PROVIDER_PENDING.name(),
          "contribution state " + candidate.state(),
          now);
      refunds.markProviderPending(refund.id(), result.providerReference(), now);
      meters.counter("refunds.success_not_confirmed").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.REFUND_COMPLETION_FAILED,
              refund.id().toString(),
              "provider succeeded but contribution state is " + candidate.state()));
      log.warn(
          "event=refund_success_not_confirmed refundId={} contributionId={} state={}",
          refund.id(),
          refund.contributionId(),
          candidate.state());
      return;
    }
    RefundStateMachine.require(refund.state(), RefundState.SUCCEEDED);
    ledger.postRefund(refund.id(), refund.contributionId(), RefundPosting.reversal(refund));
    if (feeRetained) {
      RefundPosting.loss(refund)
          .ifPresent(loss -> ledger.postRefund(refund.id(), refund.contributionId(), loss));
    }
    if (contributions.apply(refund.contributionId(), ContributionEvent.REFUND_APPROVED).isEmpty()) {
      throw new IllegalStateException("contribution could not move to REFUNDED");
    }
    if (refunds.markSucceeded(refund.id(), result.providerReference(), now) == 0) {
      throw new IllegalStateException("refund could not move to SUCCEEDED");
    }
    refunds.appendEvent(
        refund.id(),
        "SUCCEEDED",
        refund.state().name(),
        RefundState.SUCCEEDED.name(),
        result.providerReference(),
        now);
    meters.counter("refunds.succeeded").increment();
    log.info(
        "event=refund_succeeded refundId={} contributionId={}",
        refund.id(),
        refund.contributionId());
  }

  private void fail(Refund refund, RefundProvider.RefundResult result, Instant now) {
    RefundStateMachine.require(refund.state(), RefundState.FAILED);
    if (refunds.markFailed(refund.id(), result.failureCode(), now) == 0) {
      return;
    }
    refunds.appendEvent(
        refund.id(),
        "FAILED",
        refund.state().name(),
        RefundState.FAILED.name(),
        result.failureCode(),
        now);
    meters.counter("refunds.failed").increment();
    log.warn(
        "event=refund_failed refundId={} contributionId={} failureCode={}",
        refund.id(),
        refund.contributionId(),
        result.failureCode());
  }

  private void pending(Refund refund, RefundProvider.RefundResult result, Instant now) {
    refunds.markProviderPending(refund.id(), result.providerReference(), now);
    refunds.appendEvent(
        refund.id(),
        "PROVIDER_PENDING",
        refund.state().name(),
        RefundState.PROVIDER_PENDING.name(),
        result.providerReference(),
        now);
    meters.counter("refunds.provider_pending").increment();
    alerts.publish(
        new Alert(
            Alert.AlertType.REFUND_PROVIDER_PENDING,
            refund.id().toString(),
            "provider result pending"));
    log.warn(
        "event=refund_provider_pending refundId={} contributionId={}",
        refund.id(),
        refund.contributionId());
  }
}
