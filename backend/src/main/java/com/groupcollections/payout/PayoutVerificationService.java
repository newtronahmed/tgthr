package com.groupcollections.payout;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Flags EXECUTING payouts that outlive the verification threshold. The sweep never calls the
 * provider and never posts ledger: a stuck payout stays EXECUTING for operator verification. The
 * append-only event is the durable once-only marker across instances (AC-PAYOUT-027); alerting is
 * therefore at-most-once, and a failed alert is counted so operators can still find the event.
 */
@Service
@ConditionalOnProperty(name = "collections.payout.execution.enabled", havingValue = "true")
public class PayoutVerificationService {

  private static final Logger log = LoggerFactory.getLogger(PayoutVerificationService.class);
  private static final int BATCH_SIZE = 100;

  private final PayoutRepository payouts;
  private final Clock clock;
  private final Duration stuckAfter;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;

  public PayoutVerificationService(
      PayoutRepository payouts,
      Clock clock,
      @Value("${collections.payout.stuck-after:PT10M}") Duration stuckAfter,
      MeterRegistry meters,
      AlertPublisher alerts) {
    this.payouts = payouts;
    this.clock = clock;
    this.stuckAfter = stuckAfter;
    this.meters = meters;
    this.alerts = alerts;
  }

  public int verifyStuck() {
    Instant now = Instant.now(clock);
    Instant cutoff = now.minus(stuckAfter);
    int flagged = 0;
    for (Payout payout : payouts.findStuckExecuting(cutoff, BATCH_SIZE)) {
      try {
        PayoutStateMachine.transition(
            PayoutStateMachine.PayoutState.EXECUTING,
            PayoutStateMachine.PayoutEvent.VERIFICATION_REQUIRED,
            false);
        if (!payouts.markVerificationRequired(payout.id(), now)) {
          continue;
        }
        meters.counter("payout.execution.stuck").increment();
        String detail =
            "EXECUTING for more than "
                + stuckAfter
                + (payout.providerReference() == null
                    ? "; no provider reference"
                    : "; provider reference " + payout.providerReference());
        alerts.publish(
            new Alert(Alert.AlertType.PAYOUT_EXECUTION_STUCK, payout.id().toString(), detail));
        log.warn(
            "event=payout_stuck cutoff={} payoutId={} executingForMoreThan={}",
            cutoff,
            payout.id(),
            stuckAfter);
        flagged++;
      } catch (RuntimeException failure) {
        meters.counter("payout.execution.stuck_alert_failed").increment();
        log.error("event=payout_stuck_failed cutoff={} payoutId={}", cutoff, payout.id(), failure);
      }
    }
    return flagged;
  }
}
