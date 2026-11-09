package com.groupcollections.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingSetTest {

  @Test
  void rejectsAPostingSetThatMixesCurrencies() {
    assertThatThrownBy(
            () ->
                new PostingSet(
                    UUID.randomUUID(),
                    List.of(
                        new LedgerEntry(
                            LedgerAccount.PARTNER_HELD,
                            LedgerEntryType.CONTRIBUTION,
                            LedgerDirection.DEBIT,
                            Money.of(100, "GHS")),
                        new LedgerEntry(
                            LedgerAccount.GOAL_POOL,
                            LedgerEntryType.CONTRIBUTION,
                            LedgerDirection.CREDIT,
                            Money.of(100, "NGN")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one currency");
  }
}
