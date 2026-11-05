package com.groupcollections.ledger;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PostingSet(UUID id, List<LedgerEntry> entries) {

  public PostingSet {
    Objects.requireNonNull(id, "id");
    entries = List.copyOf(entries);
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("a posting set must have at least one entry");
    }
    long net = 0;
    for (LedgerEntry entry : entries) {
      net = Math.addExact(net, entry.signedPesewas());
    }
    if (net != 0) {
      throw new IllegalArgumentException("posting set must net to zero: " + net);
    }
  }
}
