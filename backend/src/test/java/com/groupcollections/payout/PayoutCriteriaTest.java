package com.groupcollections.payout;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criteria for FEAT-002, specs/features/002-payouts/spec.md.
 *
 * <p>Each test is pending: remove {@code @Disabled}, watch it fail, then implement.
 */
class PayoutCriteriaTest {

  @Test
  @Disabled("pending: cancellation slice")
  @Criterion("AC-PAYOUT-008")
  void cancellationIsOnlyPossibleBeforeExecution() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: ownership slice")
  @Criterion("AC-PAYOUT-011")
  void organizersAndApprovalTokensAreScopedToTheirPayout() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: worker slice")
  @Criterion("AC-PAYOUT-019")
  void cancelAndExecuteRaceHasOneWinner() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: cancellation/release slice")
  @Criterion("AC-PAYOUT-013")
  void cancelOrFailureReleasesReservedFunds() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: full idempotent posting slice")
  @Criterion("AC-PAYOUT-009")
  void retriedRequestDoesNotCreateASecondPosting() {
    fail("not implemented");
  }
}
