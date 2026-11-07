package com.groupcollections.ledger;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Payout-side invariant, owned by FEAT-002; FEAT-001 invariants live in LedgerPostingIT. */
class LedgerInvariantsTest {

  @Test
  @Disabled("payout scope: implemented with FEAT-002")
  @Criterion("INV-LEDGER-003")
  void payoutCannotExceedGoalPool() {
    fail("not implemented");
  }
}
