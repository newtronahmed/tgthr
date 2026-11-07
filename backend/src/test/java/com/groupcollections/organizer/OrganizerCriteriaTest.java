package com.groupcollections.organizer;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criteria for FEAT-003, specs/features/003-organizer-identity/spec.md.
 *
 * <p>Each test is pending: remove {@code @Disabled}, watch it fail, then implement (Constitution
 * III.1).
 */
class OrganizerCriteriaTest {

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-001")
  void otpRequestSendsOneCodeAndRevealsNothing() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-002")
  void validOtpCreatesARotatedSession() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-003")
  void invalidExpiredOrOverAttemptedCodesNeverCreateSessions() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-004")
  void otpRateLimitsReturn429() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-005")
  void authenticatedOrganizerCreatesAPublicGoal() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-006")
  void invalidTargetFieldsAreRejected() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-007")
  void unresolvableOrMismatchedDestinationsAreRejected() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-008")
  void retriedGoalCreationReturnsTheOriginal() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-009")
  void organizersSeeOnlyTheirOwnGoals() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-010")
  void missingOrDeadSessionsReturn401() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-011")
  void crossSiteStateChangesAreRejected() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-012")
  void organizerResponsesNeverContainPhoneNumbers() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-013")
  void ownersCloseGoalsAndContributionsStop() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-014")
  void logoutRevokesTheSession() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("AC-ORG-015")
  void resendAfterTheWindowInvalidatesThePreviousCode() {
    fail("not implemented");
  }
}
