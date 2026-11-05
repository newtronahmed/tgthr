package com.groupcollections.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ContributionPosting {

  private ContributionPosting() {}

  public static PostingSet confirmation(
      UUID contributionId, Money amount, Money processingFee, Money platformFee) {
    Money total = amount.plus(processingFee).plus(platformFee);

    List<LedgerEntry> entries = new ArrayList<>();
    entries.add(
        new LedgerEntry(
            LedgerAccount.PARTNER_HELD,
            LedgerEntryType.CONTRIBUTION,
            LedgerDirection.DEBIT,
            total));
    entries.add(
        new LedgerEntry(
            LedgerAccount.GOAL_POOL, LedgerEntryType.CONTRIBUTION, LedgerDirection.CREDIT, amount));
    if (processingFee.isPositive()) {
      entries.add(
          new LedgerEntry(
              LedgerAccount.FEE, LedgerEntryType.FEE, LedgerDirection.CREDIT, processingFee));
    }
    if (platformFee.isPositive()) {
      entries.add(
          new LedgerEntry(
              LedgerAccount.PLATFORM_FEE,
              LedgerEntryType.PLATFORM_FEE,
              LedgerDirection.CREDIT,
              platformFee));
    }

    return new PostingSet(contributionId, entries);
  }
}
