package com.groupcollections.payout;

import com.groupcollections.ledger.LedgerAccount;
import com.groupcollections.ledger.LedgerDirection;
import com.groupcollections.ledger.LedgerEntry;
import com.groupcollections.ledger.LedgerEntryType;
import com.groupcollections.ledger.Money;
import com.groupcollections.ledger.PostingSet;
import java.util.ArrayList;
import java.util.List;

/** Ledger posting for a completed payout; fee line only when the fee is positive. */
public final class PayoutPosting {

  private PayoutPosting() {}

  public static PostingSet completion(Payout payout) {
    Money amount = Money.of(payout.amountPesewas(), payout.currency());
    Money fee = Money.of(payout.feePesewas(), payout.currency());
    Money net = Money.of(payout.netPesewas(), payout.currency());
    List<LedgerEntry> entries = new ArrayList<>();
    entries.add(
        new LedgerEntry(
            LedgerAccount.GOAL_POOL, LedgerEntryType.PAYOUT, LedgerDirection.DEBIT, amount));
    entries.add(
        new LedgerEntry(
            LedgerAccount.PARTNER_HELD, LedgerEntryType.PAYOUT, LedgerDirection.CREDIT, net));
    if (fee.isPositive()) {
      entries.add(
          new LedgerEntry(
              LedgerAccount.PAYOUT_FEE, LedgerEntryType.PAYOUT_FEE, LedgerDirection.CREDIT, fee));
    }
    return new PostingSet(payout.id(), entries);
  }
}
