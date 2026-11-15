package com.groupcollections.refund;

import com.groupcollections.ledger.LedgerAccount;
import com.groupcollections.ledger.LedgerDirection;
import com.groupcollections.ledger.LedgerEntry;
import com.groupcollections.ledger.LedgerEntryType;
import com.groupcollections.ledger.Money;
import com.groupcollections.ledger.PostingSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Full pre-payout reversal and the retained-processing-fee loss set (ADR 0018). */
public final class RefundPosting {

  private RefundPosting() {}

  public static PostingSet reversal(Refund refund) {
    Money amount = Money.of(refund.amountMinorUnits(), refund.currencyCode());
    Money platformFee = Money.of(refund.platformFeeMinorUnits(), refund.currencyCode());
    Money processingFee = Money.of(refund.processingFeeMinorUnits(), refund.currencyCode());
    Money total = Money.of(refund.totalMinorUnits(), refund.currencyCode());
    LedgerAccount source = LedgerAccount.valueOf(refund.sourceAccount());

    List<LedgerEntry> entries = new ArrayList<>();
    entries.add(new LedgerEntry(source, LedgerEntryType.REVERSAL, LedgerDirection.DEBIT, amount));
    if (platformFee.isPositive()) {
      entries.add(
          new LedgerEntry(
              LedgerAccount.PLATFORM_FEE,
              LedgerEntryType.REVERSAL,
              LedgerDirection.DEBIT,
              platformFee));
    }
    if (processingFee.isPositive()) {
      entries.add(
          new LedgerEntry(
              LedgerAccount.FEE, LedgerEntryType.REVERSAL, LedgerDirection.DEBIT, processingFee));
    }
    entries.add(
        new LedgerEntry(
            LedgerAccount.PARTNER_HELD, LedgerEntryType.REVERSAL, LedgerDirection.CREDIT, total));
    return new PostingSet(refund.id(), entries);
  }

  /**
   * Posts only when the partner retains the processing fee. The partner-held credit is the fee the
   * platform owes the partner; no BANK entry is posted (ADR 0018).
   */
  public static Optional<PostingSet> loss(Refund refund) {
    Money processingFee = Money.of(refund.processingFeeMinorUnits(), refund.currencyCode());
    if (!processingFee.isPositive()) {
      return Optional.empty();
    }
    List<LedgerEntry> entries =
        List.of(
            new LedgerEntry(
                LedgerAccount.PLATFORM_LOSS,
                LedgerEntryType.LOSS_ALLOCATION,
                LedgerDirection.DEBIT,
                processingFee),
            new LedgerEntry(
                LedgerAccount.PARTNER_HELD,
                LedgerEntryType.LOSS_ALLOCATION,
                LedgerDirection.CREDIT,
                processingFee));
    UUID postingSetId =
        UUID.nameUUIDFromBytes(("refund-loss-" + refund.id()).getBytes(StandardCharsets.UTF_8));
    return Optional.of(new PostingSet(postingSetId, entries));
  }
}
