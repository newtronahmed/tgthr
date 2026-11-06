package com.groupcollections.contribution;

import com.groupcollections.payments.PaystackClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

  public ContributionTimeoutJob(
      ContributionRepository contributions,
      ContributionConfirmationService confirmation,
      PaystackClient paystack,
      Clock clock,
      @Value("${collections.contribution.timeout}") Duration timeout) {
    this.contributions = contributions;
    this.confirmation = confirmation;
    this.paystack = paystack;
    this.clock = clock;
    this.timeout = timeout;
  }

  @Scheduled(fixedDelay = 60_000)
  @Transactional
  public void run() {
    Instant cutoff = clock.instant().minus(timeout);
    List<ContributionPending> due = contributions.lockPendingDue(cutoff, BATCH_SIZE);
    for (ContributionPending pending : due) {
      if (pending.providerReference() == null || pending.providerReference().isBlank()) {
        log.warn("pending contribution {} has no provider reference", pending.id());
        continue;
      }
      switch (paystack.verify(pending.providerReference())) {
        case PAID ->
            contributions
                .findPayment(pending.id())
                .ifPresentOrElse(
                    confirmation::confirm,
                    () -> log.warn("pending contribution {} disappeared", pending.id()));
        case NOT_PAID -> contributions.apply(pending.id(), ContributionEvent.PAYMENT_TIMED_OUT);
        case UNKNOWN ->
            log.warn(
                "could not verify contribution {}; leaving it pending for the next run",
                pending.id());
      }
    }
  }
}
