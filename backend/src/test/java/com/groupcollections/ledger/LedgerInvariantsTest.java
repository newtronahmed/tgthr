package com.groupcollections.ledger;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Invariants from specs/domain/ledger.md that need a database or the posting engine.
 *
 * <p>Each test is pending: remove {@code @Disabled}, watch it fail, then implement (Constitution
 * III.1).
 */
class LedgerInvariantsTest {

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-LEDGER-001")
  void everyPostingSetNetsToZero() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-LEDGER-002")
  void entriesCannotBeUpdatedOrDeleted() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-LEDGER-003")
  void payoutCannotExceedGoalPool() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-LEDGER-004")
  void idempotencyKeyIsUniqueInTheDatabase() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-LEDGER-006")
  void balancesAreDerivedNotStored() {
    fail("not implemented");
  }
}
