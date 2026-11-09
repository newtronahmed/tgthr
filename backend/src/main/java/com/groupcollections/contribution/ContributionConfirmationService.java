package com.groupcollections.contribution;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.goal.GoalRepository;
import com.groupcollections.ledger.ContributionPosting;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.PostingSet;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Confirms a paid contribution and posts its balanced set in one transaction (AC-CONTRIB-005).
 *
 * <p>Concurrency: the database unique constraints are the correctness guarantee (Constitution
 * IV.1); the {@code SELECT ... FOR UPDATE} in {@link ContributionRepository#apply} additionally
 * serializes the transition so a second callback waits, sees the terminal state and skips the
 * ledger posting.
 */
@Service
public class ContributionConfirmationService {

  private static final Logger log = LoggerFactory.getLogger(ContributionConfirmationService.class);

  private final ContributionRepository contributions;
  private final LedgerRepository ledger;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;
  private final GoalRepository goals;
  private final Clock clock;

  public ContributionConfirmationService(
      ContributionRepository contributions,
      LedgerRepository ledger,
      AlertPublisher alerts,
      MeterRegistry meters,
      GoalRepository goals,
      Clock clock) {
    this.contributions = contributions;
    this.ledger = ledger;
    this.alerts = alerts;
    this.meters = meters;
    this.goals = goals;
    this.clock = clock;
  }

  @Transactional
  public void confirm(ContributionPayment payment) {
    ContributionTransition transition =
        contributions.apply(payment.id(), ContributionEvent.PAYMENT_SUCCEEDED).orElse(null);
    if (transition == null) {
      return;
    }
    Instant now = Instant.now(clock);
    boolean goalAccepts =
        goals
            .findByIdForShare(payment.goalId())
            .map(goal -> goal.acceptsContributions(now))
            .orElse(false);
    if (!goalAccepts) {
      contributions.markRequiresReconciliation(payment.id(), now);
    }
    PostingSet posting =
        goalAccepts
            ? ContributionPosting.confirmation(
                payment.id(), payment.amount(), payment.processingFee(), payment.platformFee())
            : ContributionPosting.suspense(
                payment.id(), payment.amount(), payment.processingFee(), payment.platformFee());
    ledger.post(payment.id(), posting);
    if (!goalAccepts) {
      afterCommit(
          () -> {
            meters.counter("contributions.suspense").increment();
            alerts.publish(
                new Alert(
                    Alert.AlertType.RECONCILIATION_REQUIRED,
                    payment.id().toString(),
                    "payment confirmed for a goal that is no longer accepting contributions"));
            log.warn(
                "event=contribution_suspense contributionId={} goalId={}",
                payment.id(),
                payment.goalId());
          });
    }
    afterCommit(
        () -> {
          meters.counter("contributions.confirmed").increment();
          log.info(
              "event=contribution_confirmed contributionId={} from={}",
              payment.id(),
              transition.from());
        });
    if (isLateSuccess(transition.from())) {
      afterCommit(
          () ->
              alerts.publish(
                  new Alert(
                      Alert.AlertType.LATE_SUCCESS,
                      payment.id().toString(),
                      "confirmed from " + transition.from())));
    }
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

  private static boolean isLateSuccess(ContributionState previous) {
    return previous == ContributionState.FAILED || previous == ContributionState.EXPIRED;
  }
}
