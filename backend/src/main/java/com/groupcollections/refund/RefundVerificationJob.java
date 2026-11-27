package com.groupcollections.refund;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded automatic re-verification of provider-pending refunds (AC-REFUND-011). Exhaustion is an
 * escalation: the reservation stays held because an unknown outcome is not proof that no refund
 * happened, and a manual verify remains available.
 */
@Component
public class RefundVerificationJob {

  private static final Logger log = LoggerFactory.getLogger(RefundVerificationJob.class);

  private final RefundRepository refunds;
  private final RefundProvider provider;
  private final RefundCompletionService completion;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final int maxAttempts;
  private final int batchSize;
  private final boolean paystack;

  public RefundVerificationJob(
      RefundRepository refunds,
      RefundProvider provider,
      RefundCompletionService completion,
      AlertPublisher alerts,
      MeterRegistry meters,
      Clock clock,
      @Value("${collections.refunds.provider:none}") String providerName,
      @Value("${collections.refunds.verification.initial-delay:PT15M}") Duration initialDelay,
      @Value("${collections.refunds.verification.max-delay:PT12H}") Duration maxDelay,
      @Value("${collections.refunds.verification.max-attempts:24}") int maxAttempts,
      @Value("${collections.refunds.verification.batch-size:20}") int batchSize) {
    this.refunds = refunds;
    this.provider = provider;
    this.completion = completion;
    this.alerts = alerts;
    this.meters = meters;
    this.clock = clock;
    this.initialDelay = initialDelay;
    this.maxDelay = maxDelay;
    this.maxAttempts = maxAttempts;
    this.batchSize = batchSize;
    this.paystack = "paystack".equals(providerName);
  }

  @Scheduled(fixedDelayString = "${collections.refunds.verification.scan-ms:60000}")
  public void run() {
    if (!paystack) {
      return;
    }
    Instant now = Instant.now(clock);
    for (RefundRepository.RefundVerificationCandidate candidate :
        refunds.findDueForVerification(now, batchSize)) {
      int attempt = candidate.attempts() + 1;
      Instant next = now.plus(backoff(attempt));
      if (refunds.claimVerification(candidate.id(), candidate.attempts(), next, now) == 0) {
        continue;
      }

      Refund refund = refunds.findById(candidate.id()).orElse(null);
      if (refund == null || refund.state() != RefundState.PROVIDER_PENDING) {
        continue;
      }
      RefundProvider.RefundResult result;
      try {
        result = provider.verify(refund);
      } catch (RuntimeException failure) {
        result =
            RefundProvider.RefundResult.pending(
                null, RefundProvider.RefundResult.PendingReason.UNKNOWN_STATUS);
      }

      refunds.appendEvent(
          refund.id(),
          "VERIFICATION_ATTEMPT",
          RefundState.PROVIDER_PENDING.name(),
          RefundState.PROVIDER_PENDING.name(),
          "attempt=" + attempt + " outcome=" + outcome(result),
          now);
      meters.counter("refunds.verification.attempt", "result", result.status().name()).increment();
      completion.process(refund.id(), result);

      Refund after = refunds.findById(refund.id()).orElse(null);
      if (after != null
          && after.state() == RefundState.PROVIDER_PENDING
          && attempt >= maxAttempts
          && refunds.markVerificationExhausted(refund.id(), now) > 0) {
        refunds.appendEvent(
            refund.id(),
            "VERIFICATION_EXHAUSTED",
            RefundState.PROVIDER_PENDING.name(),
            RefundState.PROVIDER_PENDING.name(),
            "attempts=" + maxAttempts,
            now);
        meters.counter("refunds.verification.exhausted").increment();
        alerts.publish(
            new Alert(
                Alert.AlertType.REFUND_VERIFICATION_EXHAUSTED,
                refund.id().toString(),
                "automatic verification exhausted; reservation held"));
        log.warn(
            "event=refund_verification_exhausted refundId={} contributionId={} attempts={}",
            refund.id(),
            refund.contributionId(),
            maxAttempts);
      }
    }
  }

  private Duration backoff(int attempt) {
    int exponent = Math.min(Math.max(attempt - 1, 0), 20);
    Duration delay = initialDelay.multipliedBy(1L << exponent);
    return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
  }

  private static String outcome(RefundProvider.RefundResult result) {
    return result.pendingReason() == null
        ? result.status().name()
        : result.status().name() + ":" + result.pendingReason().name();
  }
}
