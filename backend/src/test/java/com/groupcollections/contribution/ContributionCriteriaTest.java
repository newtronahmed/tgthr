package com.groupcollections.contribution;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criteria for FEAT-001, specs/features/001-contributions/spec.md.
 *
 * <p>Each test is pending: remove {@code @Disabled}, watch it fail, then implement (Constitution III.1).
 */
class ContributionCriteriaTest {

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-001")
  void promptRequestedAndStateBecomesPending() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-002")
  void fixedGoalRejectsDifferentAmount() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-003")
  void openGoalRejectsAmountBelowMinimum() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-004")
  void duplicateSuccessCallbackPostsOnce() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-005")
  void successConfirmsAndPostsBalancedEntriesInOneTransaction() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-006")
  void failedCallbackLeavesLedgerUntouched() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-007")
  void invalidSignatureIsRejectedWithoutStateChange() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-008")
  void statusJobAppliesFinalPartnerStatusAfterTimeout() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-009")
  void retryAfterTerminalStateCreatesNewContribution() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-010")
  void anonymousGiftHidesNameOnPublicFeed() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-011")
  void publicResponsesNeverContainPhoneNumbers() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-012")
  void closedFrozenOrExpiredGoalRejectsContributions() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-013")
  void receiptAppearsOnlyWhenConfirmed() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-014")
  void feeAndLedgerEntriesBalanceForEveryAllowedAmount() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-015")
  void retriedRequestWithSameKeyReturnsOriginal() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-016")
  void lateSuccessAfterExpiryOrFailureConfirmsWithAlert() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-CONTRIB-017")
  void platformFeeIsOneAndAHalfPercentRoundedUpOnTop() {
    fail("not implemented");
  }
}
