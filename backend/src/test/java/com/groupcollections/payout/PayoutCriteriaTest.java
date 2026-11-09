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
  @Disabled("pending: payout request/eligibility slice")
  @Criterion("AC-PAYOUT-001")
  void partialPayoutUsesSettledAvailableBalance() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: payout request/eligibility slice")
  @Criterion("AC-PAYOUT-002")
  void unsettledOrSuspenseFundsAreNotPayoutEligible() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: payout request/eligibility slice")
  @Criterion("AC-PAYOUT-003")
  void feeAndBeneficiaryNetAreExactAndGoalBorne() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: approval slice")
  @Criterion("AC-PAYOUT-004")
  void requiredApprovalGatesThePayoutAndDeclineCancels() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: approval slice")
  @Criterion("AC-PAYOUT-005")
  void approvalCodesAreSingleUseExpiringAndAttemptLimited() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: approver setup slice")
  @Criterion("AC-PAYOUT-006")
  void approverIsRequiredDistinctEncryptedAndOnlyTheNameIsPublic() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: hold/freeze slice")
  @Criterion("AC-PAYOUT-007")
  void holdExpiryAndFreezePrecedenceAreEnforced() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: cancellation slice")
  @Criterion("AC-PAYOUT-008")
  void cancellationIsOnlyPossibleBeforeExecution() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: execution/idempotency slice")
  @Criterion("AC-PAYOUT-009")
  void retriedRequestReturnsTheOriginalPayout() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: execution/ledger slice")
  @Criterion("AC-PAYOUT-010")
  void successPostsTheLedgerOnceAndFailureDoesNotRetry() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: ownership slice")
  @Criterion("AC-PAYOUT-011")
  void organizersAndApprovalTokensAreScopedToTheirPayout() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: reservation slice")
  @Criterion("AC-PAYOUT-013")
  void inFlightPayoutsReserveAvailableFunds() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: contributor visibility slice")
  @Criterion("AC-PAYOUT-014")
  void contributorsSeeReportAndAreNotifiedOfPayouts() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: fee re-check slice")
  @Criterion("AC-PAYOUT-015")
  void providerFeeChangeFailsBeforeAnyTransfer() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: hold slice")
  @Criterion("AC-PAYOUT-016")
  void zeroHoldIsRejected() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: approval slice")
  @Criterion("AC-PAYOUT-017")
  void resendInvalidatesPriorApprovalAndExpiryCancels() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: idempotency slice")
  @Criterion("AC-PAYOUT-018")
  void sameKeyDifferentBodyReturnsConflict() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: worker slice")
  @Criterion("AC-PAYOUT-019")
  void cancelAndExecuteRaceHasOneWinner() {
    fail("not implemented");
  }
}
