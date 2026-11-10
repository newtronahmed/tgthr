package com.groupcollections.payout;

import com.groupcollections.goal.OrganizerGoalRepository;
import com.groupcollections.ledger.LedgerRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Posts the one completion ledger set and notifies contributors after commit. */
@Service
public class PayoutCompletionService {

  private final PayoutRepository payouts;
  private final LedgerRepository ledger;
  private final OrganizerGoalRepository goals;
  private final PayoutNotificationService notifications;

  public PayoutCompletionService(
      PayoutRepository payouts,
      LedgerRepository ledger,
      OrganizerGoalRepository goals,
      PayoutNotificationService notifications) {
    this.payouts = payouts;
    this.ledger = ledger;
    this.goals = goals;
    this.notifications = notifications;
  }

  @Transactional
  public void complete(Payout payout, String providerReference, Instant now) {
    if (!goals.lockByIdAndCreator(payout.goalId(), payout.requestedBy())) {
      throw new IllegalStateException("payout goal missing");
    }
    PayoutStateMachine.transition(
        PayoutStateMachine.PayoutState.EXECUTING,
        PayoutStateMachine.PayoutEvent.PROVIDER_SUCCEEDED,
        false);
    ledger.postPayout(payout.id(), PayoutPosting.completion(payout));
    if (payouts.markCompleted(payout.id(), providerReference, now) != 1) {
      throw new IllegalStateException("payout is not executing");
    }
    payouts.appendEvent(payout.id(), "COMPLETED", "EXECUTING", "COMPLETED", now);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              notifications.notifyCompleted(payout.id());
            }
          });
    } else {
      notifications.notifyCompleted(payout.id());
    }
  }

  @Transactional
  public void fail(Payout payout, String failureCode, Instant now) {
    PayoutStateMachine.transition(
        PayoutStateMachine.PayoutState.EXECUTING,
        PayoutStateMachine.PayoutEvent.PROVIDER_FAILED,
        false);
    if (payouts.markFailed(payout.id(), failureCode, "EXECUTING", now) != 1) {
      throw new IllegalStateException("payout is not executing");
    }
    payouts.appendEvent(payout.id(), "FAILED", "EXECUTING", "FAILED", now);
  }
}
