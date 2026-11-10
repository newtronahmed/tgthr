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
  @Disabled("pending: worker slice")
  @Criterion("AC-PAYOUT-019")
  void cancelAndExecuteRaceHasOneWinner() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: fee/ledger slice")
  @Criterion("AC-PAYOUT-003")
  void zeroFeeOmitsTheFeeLineAndCompletionPostsTheDebit() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: execution/idempotency slice")
  @Criterion("AC-PAYOUT-009")
  void retriedRequestDoesNotCreateASecondPosting() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: cancellation/release slice")
  @Criterion("AC-PAYOUT-013")
  void cancelOrFailureReleasesReservedFunds() {
    fail("not implemented");
  }
}
