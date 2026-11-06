package com.groupcollections.contribution;

import com.groupcollections.ledger.ContributionPosting;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.PostingSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Confirms a paid contribution and posts its balanced set in one transaction (AC-CONTRIB-005). */
@Service
public class ContributionConfirmationService {

  private final ContributionRepository contributions;
  private final LedgerRepository ledger;

  public ContributionConfirmationService(
      ContributionRepository contributions, LedgerRepository ledger) {
    this.contributions = contributions;
    this.ledger = ledger;
  }

  @Transactional
  public void confirm(ContributionPayment payment) {
    if (!contributions.apply(payment.id(), ContributionEvent.PAYMENT_SUCCEEDED)) {
      return;
    }
    PostingSet posting =
        ContributionPosting.confirmation(
            payment.id(), payment.amount(), payment.processingFee(), payment.platformFee());
    ledger.post(payment.id(), posting);
  }
}
