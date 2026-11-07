package com.groupcollections.organizer;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Invariants from specs/domain/organizer-identity.md.
 *
 * <p>Each test is pending: remove {@code @Disabled}, watch it fail, then implement (Constitution
 * III.1).
 */
class OrganizerIdentityInvariantsTest {

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-IDENT-001")
  void protectedRequestsRequireALiveUnrevokedSession() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-IDENT-002")
  void challengesAreSingleUseExpiringAndAttemptLimited() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-IDENT-003")
  void phoneNumbersAreEncryptedAndNeverReturned() {
    fail("not implemented");
  }
}
