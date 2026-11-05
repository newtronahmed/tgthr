package com.groupcollections.contribution;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Invariants from specs/domain/contribution-state-machine.md.
 *
 * <p>Each test is pending: remove {@code @Disabled}, watch it fail, then implement (Constitution III.1).
 */
class ContributionStateMachineTest {

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-STATE-001")
  void onlyListedTransitionsChangeState() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-STATE-002")
  void ledgerEntriesExistOnlyForConfirmedContributions() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-STATE-003")
  void everyStateChangeAppendsHistory() {
    fail("not implemented");
  }
}
