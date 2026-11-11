package com.groupcollections.payout;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Executes claimed payouts outside the claim transaction; one provider call per claim. */
@Service
@ConditionalOnProperty(name = "collections.payout.execution.enabled", havingValue = "true")
public class PayoutExecutionService {

  private static final Logger log = LoggerFactory.getLogger(PayoutExecutionService.class);

  private final PayoutClaimService claims;
  private final PayoutTransferProvider provider;
  private final PayoutCompletionService completion;
  private final PayoutRepository payouts;
  private final Clock clock;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;

  public PayoutExecutionService(
      PayoutClaimService claims,
      PayoutTransferProvider provider,
      PayoutCompletionService completion,
      PayoutRepository payouts,
      Clock clock,
      MeterRegistry meters,
      AlertPublisher alerts) {
    this.claims = claims;
    this.provider = provider;
    this.completion = completion;
    this.payouts = payouts;
    this.clock = clock;
    this.meters = meters;
    this.alerts = alerts;
  }

  private void markUnknown(Payout payout, Instant now) {
    PayoutStateMachine.transition(
        PayoutStateMachine.PayoutState.EXECUTING,
        PayoutStateMachine.PayoutEvent.PROVIDER_UNKNOWN,
        false);
    payouts.appendEvent(payout.id(), "PROVIDER_UNKNOWN", "EXECUTING", "EXECUTING", now);
    meters.counter("payout.execution.unknown").increment();
    alerts.publish(
        new Alert(
            Alert.AlertType.PAYOUT_EXECUTION_STUCK,
            payout.id().toString(),
            "payout provider result is unknown"));
    log.warn("payout provider result unknown {}", payout.id());
  }

  public long reportFrozenBlocked() {
    long frozen = payouts.countFrozenReady();
    if (frozen > 0) {
      meters.counter("payout.execution.frozen_blocked").increment(frozen);
      alerts.publish(
          new Alert(
              Alert.AlertType.PAYOUT_BLOCKED_FROZEN,
              "payout-worker",
              frozen + " READY payout(s) blocked by frozen goals"));
    }
    return frozen;
  }

  public int executeNext() {
    Instant now = Instant.now(clock);
    Optional<Payout> claimed = claims.claimNext(now);
    if (claimed.isEmpty()) {
      return 0;
    }
    Payout payout = claimed.get();
    PayoutTransferProvider.TransferResult result;
    try {
      result = provider.transfer(payout);
    } catch (RuntimeException failure) {
      markUnknown(payout, now);
      return 1;
    }
    if (result.status() == PayoutTransferProvider.TransferResult.Status.SUCCESS
        && (result.providerReference() == null || result.providerReference().isBlank())) {
      markUnknown(payout, now);
      return 1;
    }
    if (result.providerReference() != null && !result.providerReference().isBlank()) {
      if (payouts.recordProviderReference(payout.id(), result.providerReference(), now) != 1) {
        meters.counter("payout.execution.reference_rejected").increment();
        alerts.publish(
            new Alert(
                Alert.AlertType.PAYOUT_EXECUTION_STUCK,
                payout.id().toString(),
                "provider reference could not be persisted"));
        return 1;
      }
    }
    switch (result.status()) {
      case SUCCESS -> {
        try {
          completion.complete(payout, result.providerReference(), now);
        } catch (RuntimeException failure) {
          meters.counter("payout.execution.stuck").increment();
          alerts.publish(
              new Alert(
                  Alert.AlertType.PAYOUT_EXECUTION_STUCK,
                  payout.id().toString(),
                  "provider succeeded but completion was not persisted"));
          log.warn("payout completion stuck {}", payout.id());
        }
      }
      case FAILED -> {
        completion.fail(
            payout, result.failureCode() == null ? "PROVIDER_FAILED" : result.failureCode(), now);
        meters.counter("payout.execution.failed").increment();
      }
      case UNKNOWN -> markUnknown(payout, now);
    }
    return 1;
  }
}
