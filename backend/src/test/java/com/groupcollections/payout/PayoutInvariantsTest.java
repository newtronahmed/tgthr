package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.payout.PayoutStateMachine.PayoutEvent;
import com.groupcollections.payout.PayoutStateMachine.PayoutState;
import com.groupcollections.support.Criterion;
import java.util.Map;
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
  @Criterion("INV-PAYOUT-002")
  void onlyDeclaredTransitionsAreLegal() {
    Map<String, PayoutState> legal =
        Map.ofEntries(
            Map.entry("REQUESTED:APPROVE", PayoutState.APPROVED),
            Map.entry("REQUESTED:DECLINE", PayoutState.CANCELLED),
            Map.entry("REQUESTED:CANCEL", PayoutState.CANCELLED),
            Map.entry("REQUESTED:APPROVAL_EXPIRED", PayoutState.CANCELLED),
            Map.entry("REQUESTED:APPROVAL_EXHAUSTED", PayoutState.CANCELLED),
            Map.entry("APPROVED:START_HOLD", PayoutState.HOLD),
            Map.entry("APPROVED:CANCEL", PayoutState.CANCELLED),
            Map.entry("HOLD:HOLD_EXPIRED", PayoutState.READY),
            Map.entry("HOLD:CANCEL", PayoutState.CANCELLED),
            Map.entry("READY:CLAIM", PayoutState.EXECUTING),
            Map.entry("READY:FEE_MISMATCH", PayoutState.FAILED),
            Map.entry("READY:CANCEL", PayoutState.CANCELLED),
            Map.entry("EXECUTING:PROVIDER_SUCCEEDED", PayoutState.COMPLETED),
            Map.entry("EXECUTING:PROVIDER_FAILED", PayoutState.FAILED),
            Map.entry("EXECUTING:PROVIDER_UNKNOWN", PayoutState.EXECUTING));

    for (PayoutState from : PayoutState.values()) {
      for (PayoutEvent event : PayoutEvent.values()) {
        PayoutState expected = legal.get(from + ":" + event);
        if (expected == null) {
          assertThat(PayoutStateMachine.canTransition(from, event, false)).isFalse();
          assertThatThrownBy(() -> PayoutStateMachine.transition(from, event, false))
              .isInstanceOf(IllegalStateException.class);
        } else {
          assertThat(PayoutStateMachine.canTransition(from, event, false)).isTrue();
          assertThat(PayoutStateMachine.transition(from, event, false)).isEqualTo(expected);
        }
      }
    }

    assertThat(PayoutStateMachine.canTransition(PayoutState.READY, PayoutEvent.CLAIM, true))
        .isFalse();
    assertThatThrownBy(
            () -> PayoutStateMachine.transition(PayoutState.READY, PayoutEvent.CLAIM, true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @Disabled("pending: hold/freeze slice")
  @Criterion("INV-PAYOUT-003")
  void freezeAndCancellationRulesHold() {
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
