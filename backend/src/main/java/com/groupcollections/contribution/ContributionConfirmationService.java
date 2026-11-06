package com.groupcollections.contribution;

import com.groupcollections.ledger.ContributionPosting;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.PostingSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Confirms a paid contribution and posts its balanced set in one transaction (AC-CONTRIB-005). */
@Service
public class ContributionConfirmationService {

  private static final Logger log = LoggerFactory.getLogger(ContributionConfirmationService.class);

  private final ContributionRepository contributions;
  private final LedgerRepository ledger;

  public ContributionConfirmationService(
      ContributionRepository contributions, LedgerRepository ledger) {
    this.contributions = contributions;
    this.ledger = ledger;
  }

  @Transactional
  public void confirm(ContributionPayment payment) {
    ContributionTransition transition =
        contributions.apply(payment.id(), ContributionEvent.PAYMENT_SUCCEEDED).orElse(null);
    if (transition == null) {
      return;
    }
    if (transition.from() == ContributionState.FAILED
        || transition.from() == ContributionState.EXPIRED) {
      log.error(
          "ALERT: late SUCCESS confirmed contribution {} from {}", payment.id(), transition.from());
    }
    PostingSet posting =
        ContributionPosting.confirmation(
            payment.id(), payment.amount(), payment.processingFee(), payment.platformFee());
    ledger.post(payment.id(), posting);
  }
}
