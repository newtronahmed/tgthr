package com.groupcollections.ledger;

import java.util.Objects;

public record LedgerEntry(
    LedgerAccount account, LedgerEntryType type, LedgerDirection direction, Money amount) {

  public LedgerEntry {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("posted entries must be strictly positive");
    }
  }

  public long signedPesewas() {
    return direction == LedgerDirection.DEBIT ? amount.pesewas() : -amount.pesewas();
  }
}
