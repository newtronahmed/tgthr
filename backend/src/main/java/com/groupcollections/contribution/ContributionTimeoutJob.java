package com.groupcollections.contribution;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.payments.PaymentStatus;
import com.groupcollections.payments.PaymentVerification;
import com.groupcollections.payments.PaystackClient;
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
 * Resolves PENDING contributions past the timeout by asking the partner for the final status
 * (AC-CONTRIB-008). An unknown status leaves the row pending for the next run.
 */
@Component
public class ContributionTimeoutJob {

  private static final Logger log = LoggerFactory.getLogger(ContributionTimeoutJob.class);
  private static final int BATCH_SIZE = 100;

  private final ContributionRepository contributions;
  private final ContributionConfirmationService confirmation;
  private final PaystackClient paystack;
  private final Clock clock;
  private final Duration timeout;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;

  public ContributionTimeoutJob(
      ContributionRepository contributions,
      ContributionConfirmationService confirmation,
      PaystackClient paystack,
      Clock clock,
      @Value("${collections.contribution.timeout}") Duration timeout,
      MeterRegistry meters,
      AlertPublisher alerts) {
    this.contributions = contributions;
    this.confirmation = confirmation;
    this.paystack = paystack;
    this.clock = clock;
    this.timeout = timeout;
    this.meters = meters;
    this.alerts = alerts;
  }

  @Scheduled(fixedDelay = 60_000)
  public void run() {
    Instant cutoff = clock.instant().minus(timeout);
    Instant stuckBefore = clock.instant().minus(timeout.multipliedBy(2));
    for (ContributionPending pending : contributions.findPendingDue(cutoff, BATCH_SIZE)) {
      if (pending.providerReference() == null || pending.providerReference().isBlank()) {
        log.warn("pending contribution {} has no provider reference", pending.id());
        continue;
      }
      if (pending.updatedAt().isBefore(stuckBefore)) {
        alerts.publish(
            new Alert(
                Alert.AlertType.STUCK_PENDING,
                pending.providerReference(),
                "pending for more than " + timeout.multipliedBy(2)));
        meters.counter("contributions.timeout", "result", "stuck").increment();
      }
      try {
        resolve(pending);
      } catch (RuntimeException failure) {
        log.error("could not resolve contribution {}", pending.id(), failure);
      }
    }
    for (ContributionPending stale : contributions.findInitiatedStale(cutoff, BATCH_SIZE)) {
      if (stale.providerReference() == null || stale.providerReference().isBlank()) {
        continue;
      }
      try {
        resolveStaleInitiated(stale);
      } catch (RuntimeException failure) {
        log.error("could not resolve stale contribution {}", stale.id(), failure);
      }
    }
    Instant lookbackStart = cutoff.minus(Duration.ofHours(24));
    for (ContributionPending terminal :
        contributions.findTerminalForRecheck(cutoff, lookbackStart, BATCH_SIZE)) {
      if (terminal.providerReference() == null || terminal.providerReference().isBlank()) {
        continue;
      }
      try {
        recheck(terminal);
      } catch (RuntimeException failure) {
        log.error("could not re-check contribution {}", terminal.id(), failure);
      }
    }
  }

  private void resolve(ContributionPending pending) {
    PaymentStatus status = paystack.verify(pending.providerReference());
    switch (status.outcome()) {
      case PAID -> confirmIfMatching(pending, status, "confirmed");
      case FAILED -> {
        contributions.apply(pending.id(), ContributionEvent.PAYMENT_FAILED);
        resolved(pending, "failed");
      }
      case NO_PAYMENT -> {
        contributions.apply(pending.id(), ContributionEvent.PAYMENT_TIMED_OUT);
        resolved(pending, "expired");
      }
      case UNKNOWN -> {
        meters.counter("contributions.timeout", "result", "unknown").increment();
        log.warn(
            "could not verify contribution {}; leaving it pending for the next run", pending.id());
      }
    }
  }

  private void resolveStaleInitiated(ContributionPending stale) {
    PaymentStatus status = paystack.verify(stale.providerReference());
    switch (status.outcome()) {
      case PAID -> confirmIfMatching(stale, status, "confirmed");
      case FAILED, NO_PAYMENT -> {
        contributions.apply(stale.id(), ContributionEvent.PAYMENT_TIMED_OUT);
        resolved(stale, "expired");
      }
      case UNKNOWN -> {
        meters.counter("contributions.timeout", "result", "unknown").increment();
        log.warn("could not verify stale contribution {}; leaving it for the next run", stale.id());
      }
    }
  }

  private void recheck(ContributionPending terminal) {
    PaymentStatus status = paystack.verify(terminal.providerReference());
    if (status.outcome() == PaymentVerification.PAID) {
      confirmIfMatching(terminal, status, "reconfirmed");
    }
  }

  private void confirmIfMatching(
      ContributionPending pending, PaymentStatus status, String metricResult) {
    contributions
        .findPayment(pending.id())
        .ifPresentOrElse(
            payment -> {
              if (status.amountPesewas() != payment.total().minorUnits()
                  || !payment.total().currencyCode().equals(status.currency())) {
                alerts.publish(
                    new Alert(
                        Alert.AlertType.VERIFY_MISMATCH,
                        pending.providerReference(),
                        "expected "
                            + payment.total().minorUnits()
                            + " GHS, got "
                            + status.amountPesewas()
                            + " "
                            + status.currency()));
                meters.counter("contributions.timeout", "result", "verify_mismatch").increment();
                return;
              }
              confirmation.confirm(payment);
              resolved(pending, metricResult);
            },
            () -> log.warn("contribution {} disappeared during verification", pending.id()));
  }

  private void resolved(ContributionPending pending, String result) {
    meters.counter("contributions.timeout", "result", result).increment();
    log.info("event=timeout_resolved contributionId={} result={}", pending.id(), result);
  }
}
