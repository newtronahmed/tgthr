package com.groupcollections.payout;

import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Invariants from specs/domain/payout-state-machine.md. */
class PayoutInvariantsTest {

  @Test
  @Disabled("pending: eligibility slice")
  @Criterion("INV-PAYOUT-001")
  void payoutNeverExceedsSettledAvailableBalance() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: state machine slice")
  @Criterion("INV-PAYOUT-002")
  void onlyDeclaredTransitionsAreLegal() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: hold/freeze slice")
  @Criterion("INV-PAYOUT-003")
  void freezeAndCancellationRulesHold() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: approval slice")
  @Criterion("INV-PAYOUT-004")
  void approvalChallengesAreSingleUseExpiringAndAttemptLimited() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: execution slice")
  @Criterion("INV-PAYOUT-005")
  void ledgerPostsOnceOnlyOnCompletion() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: privacy slice")
  @Criterion("INV-PAYOUT-006")
  void approverAndDestinationPhonesAreEncryptedAndNeverPublic() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: reservation slice")
  @Criterion("INV-PAYOUT-007")
  void activePayoutsCannotDoubleSpendAvailableFunds() {
    fail("not implemented");
  }

  @Test
  @Disabled("pending: contributor visibility slice")
  @Criterion("INV-PAYOUT-008")
  void heldAndSentPayoutsAreReportableAndNotifyContributorsOnce() {
    fail("not implemented");
  }
}
