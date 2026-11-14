package com.groupcollections.refund;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criteria for FEAT-005, specs/features/005-refunds-and-operator-tooling/spec.md.
 *
 * <p>Each test is pending: the FEAT-005 plan and its implementation slices remove @Disabled, watch
 * the test fail, then implement it. P1 decisions are recorded in the spec and ADR 0017.
 */
class RefundCriteriaTest {

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-001")
  void refundEligibilityReservesBeforeProvider() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-002")
  void refundSuccessPostsOneReversal() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-003")
  void refundProviderFailureReleasesReservation() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-004")
  void refundProviderUnknownStaysPending() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-005")
  void refundBlockedByActiveOrCompletedPayout() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-006")
  void concurrentRefundRequestsHaveOneWinner() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-007")
  void fullRefundBalancesLedgerWithOrWithoutReturnedFee() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-REFUND-008")
  void nonOperatorAndPhoneAreRejected() {
    fail("not implemented");
  }

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
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-001")
  void reconciliationQueueListsSuspenseWithoutPhones() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-002")
  void reclassifySuspenseToGoalPool() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-003")
  void refundSuspenseOnce() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-004")
  void miscIncomeAfterTimeout() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-005")
  void operatorActionRequiresRoleReasonAndKey() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-006")
  void concurrentReconciliationHasOneWinner() {
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

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-009")
  void miscIncomeRequiresTwoDistinctOperators() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: FEAT-005 plan")
  @Criterion("AC-OPS-010")
  void sensitiveActionsRequireFreshTotp() {
    fail("not implemented");
  }
}
