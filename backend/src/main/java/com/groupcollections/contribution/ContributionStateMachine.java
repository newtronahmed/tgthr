package com.groupcollections.contribution;

import java.util.Map;
import java.util.Optional;

/** The single transition table from specs/domain/contribution-state-machine.md. */
public final class ContributionStateMachine {

  private static final Map<ContributionState, Map<ContributionEvent, ContributionState>>
      TRANSITIONS =
          Map.of(
              ContributionState.INITIATED,
              Map.of(
                  ContributionEvent.PROMPT_SENT,
                  ContributionState.PENDING,
                  ContributionEvent.PROMPT_REJECTED,
                  ContributionState.FAILED),
              ContributionState.PENDING,
              Map.of(
                  ContributionEvent.PAYMENT_SUCCEEDED,
                  ContributionState.CONFIRMED,
                  ContributionEvent.PAYMENT_FAILED,
                  ContributionState.FAILED,
                  ContributionEvent.PAYMENT_TIMED_OUT,
                  ContributionState.EXPIRED),
              ContributionState.EXPIRED,
              Map.of(ContributionEvent.PAYMENT_SUCCEEDED, ContributionState.CONFIRMED),
              ContributionState.FAILED,
              Map.of(ContributionEvent.PAYMENT_SUCCEEDED, ContributionState.CONFIRMED),
              ContributionState.CONFIRMED,
              Map.of(ContributionEvent.REFUND_APPROVED, ContributionState.REFUNDED),
              ContributionState.REFUNDED,
              Map.of());

  private ContributionStateMachine() {}

  /**
   * Returns the transition for a legal event, or empty when the event is illegal for the state. An
   * empty result means the state is unchanged and no history row is appended.
   */
  public static Optional<ContributionTransition> apply(
      ContributionState from, ContributionEvent event) {
    return Optional.ofNullable(TRANSITIONS.getOrDefault(from, Map.of()).get(event))
        .map(to -> new ContributionTransition(from, to, event));
  }
}
