package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Invariants from specs/domain/contribution-state-machine.md. The table is the only place a state
 * may change; anything else leaves the state untouched and appends no history.
 */
class ContributionStateMachineTest {

  @Test
  @Criterion("INV-STATE-001")
  void onlyListedTransitionsChangeState() {
    assertTransition(
        ContributionState.INITIATED, ContributionEvent.PROMPT_SENT, ContributionState.PENDING);
    assertTransition(
        ContributionState.INITIATED, ContributionEvent.PROMPT_REJECTED, ContributionState.FAILED);
    assertTransition(
        ContributionState.INITIATED,
        ContributionEvent.PAYMENT_SUCCEEDED,
        ContributionState.CONFIRMED);
    assertTransition(
        ContributionState.PENDING,
        ContributionEvent.PAYMENT_SUCCEEDED,
        ContributionState.CONFIRMED);
    assertTransition(
        ContributionState.PENDING, ContributionEvent.PAYMENT_FAILED, ContributionState.FAILED);
    assertTransition(
        ContributionState.PENDING, ContributionEvent.PAYMENT_TIMED_OUT, ContributionState.EXPIRED);
    assertTransition(
        ContributionState.EXPIRED,
        ContributionEvent.PAYMENT_SUCCEEDED,
        ContributionState.CONFIRMED);
    assertTransition(
        ContributionState.FAILED, ContributionEvent.PAYMENT_SUCCEEDED, ContributionState.CONFIRMED);
    assertTransition(
        ContributionState.CONFIRMED, ContributionEvent.REFUND_APPROVED, ContributionState.REFUNDED);

    assertThat(
            ContributionStateMachine.apply(
                ContributionState.CONFIRMED, ContributionEvent.PROMPT_SENT))
        .isEmpty();
    assertThat(
            ContributionStateMachine.apply(
                ContributionState.REFUNDED, ContributionEvent.PAYMENT_SUCCEEDED))
        .isEmpty();
    assertThat(
            ContributionStateMachine.apply(
                ContributionState.PENDING, ContributionEvent.REFUND_APPROVED))
        .isEmpty();
    assertThat(
            ContributionStateMachine.apply(
                ContributionState.EXPIRED, ContributionEvent.PAYMENT_FAILED))
        .isEmpty();
  }

  @Test
  @Disabled("pending: write the failing test first")
  @Criterion("INV-STATE-002")
  void ledgerEntriesExistOnlyForConfirmedContributions() {
    fail("not implemented");
  }

  @Test
  @Criterion("INV-STATE-003")
  void everyStateChangeCarriesTheHistoryRowToAppend() {
    ContributionTransition transition =
        ContributionStateMachine.apply(
                ContributionState.PENDING, ContributionEvent.PAYMENT_SUCCEEDED)
            .orElseThrow();

    assertThat(transition.from()).isEqualTo(ContributionState.PENDING);
    assertThat(transition.to()).isEqualTo(ContributionState.CONFIRMED);
    assertThat(transition.event()).isEqualTo(ContributionEvent.PAYMENT_SUCCEEDED);

    assertThat(
            ContributionStateMachine.apply(
                ContributionState.PENDING, ContributionEvent.REFUND_APPROVED))
        .isEmpty();
  }

  private static void assertTransition(
      ContributionState from, ContributionEvent event, ContributionState to) {
    assertThat(ContributionStateMachine.apply(from, event))
        .contains(new ContributionTransition(from, to, event));
  }
}
