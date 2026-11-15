package com.groupcollections.refund;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * P2 acceptance-criteria placeholders for FEAT-005,
 * specs/features/005-refunds-and-operator-tooling/spec.md.
 *
 * <p>Every P1 criterion now has an active integration test; the remaining placeholders are removed
 * when the P2 slice lands.
 */
class RefundCriteriaTest {

  @Test
  @Disabled("pending: FEAT-005 P2")
  @Criterion("AC-REFUND-009")
  void postPayoutRecoveryRequestIsAudited() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 P2")
  @Criterion("AC-REFUND-010")
  void lossAllocationMovesShortfallToPlatformLoss() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 P2")
  @Criterion("AC-OPS-007")
  void settlementUploadIsIdempotent() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 P2")
  @Criterion("AC-OPS-008")
  void payoutAndNotificationReviewDoesNotReexecute() {
    fail("not implemented");
  }
}
