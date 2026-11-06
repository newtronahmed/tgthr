package com.groupcollections.contribution;

import com.groupcollections.ledger.ContributionPosting;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.PostingSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Confirms a paid contribution and posts its balanced set in one transaction (AC-CONTRIB-005). */
@Service
public class ContributionConfirmationService {

  private final ContributionRepository contributions;
  private final LedgerRepository ledger;
  private final AlertPublisher alerts;

  public ContributionConfirmationService(
      ContributionRepository contributions, LedgerRepository ledger, AlertPublisher alerts) {
    this.contributions = contributions;
    this.ledger = ledger;
    this.alerts = alerts;
  }

  @Transactional
  public void confirm(ContributionPayment payment) {
    ContributionTransition transition =
        contributions.apply(payment.id(), ContributionEvent.PAYMENT_SUCCEEDED).orElse(null);
    if (transition == null) {
      return;
    }
    PostingSet posting =
        ContributionPosting.confirmation(
            payment.id(), payment.amount(), payment.processingFee(), payment.platformFee());
    ledger.post(payment.id(), posting);
    if (isLateSuccess(transition.from())) {
      publishAfterCommit(
          new Alert(
              Alert.AlertType.LATE_SUCCESS,
              payment.id().toString(),
              "confirmed from " + transition.from()));
    }
  }

  private void publishAfterCommit(Alert alert) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              alerts.publish(alert);
            }
          });
    } else {
      alerts.publish(alert);
    }
  }

  private static boolean isLateSuccess(ContributionState previous) {
    return previous == ContributionState.FAILED || previous == ContributionState.EXPIRED;
  }
}
