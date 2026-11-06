package com.groupcollections.contribution;

import com.groupcollections.ledger.ContributionPosting;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.PostingSet;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Confirms a paid contribution and posts its balanced set in one transaction (AC-CONTRIB-005). */
@Service
public class ContributionConfirmationService {

  private static final Logger log = LoggerFactory.getLogger(ContributionConfirmationService.class);

  private final ContributionRepository contributions;
  private final LedgerRepository ledger;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;

  public ContributionConfirmationService(
      ContributionRepository contributions,
      LedgerRepository ledger,
      AlertPublisher alerts,
      MeterRegistry meters) {
    this.contributions = contributions;
    this.ledger = ledger;
    this.alerts = alerts;
    this.meters = meters;
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
    meters.counter("contributions.confirmed").increment();
    log.info(
        "event=contribution_confirmed contributionId={} from={}", payment.id(), transition.from());
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
