package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RefundStateMachineTest {

  @Test
  void allowsOnlyTheProviderTransitions() {
    assertThat(RefundStateMachine.canTransition(RefundState.PENDING, RefundState.PROVIDER_PENDING))
        .isTrue();
    assertThat(RefundStateMachine.canTransition(RefundState.PENDING, RefundState.SUCCEEDED))
        .isTrue();
    assertThat(RefundStateMachine.canTransition(RefundState.PENDING, RefundState.FAILED)).isTrue();
    assertThat(
            RefundStateMachine.canTransition(RefundState.PROVIDER_PENDING, RefundState.SUCCEEDED))
        .isTrue();
    assertThat(RefundStateMachine.canTransition(RefundState.PROVIDER_PENDING, RefundState.FAILED))
        .isTrue();
  }

  @Test
  void terminalStatesNeverMoveAgain() {
    for (RefundState terminal : new RefundState[] {RefundState.SUCCEEDED, RefundState.FAILED}) {
      for (RefundState target : RefundState.values()) {
        assertThat(RefundStateMachine.canTransition(terminal, target)).isFalse();
        assertThatThrownBy(() -> RefundStateMachine.require(terminal, target))
            .isInstanceOf(IllegalStateException.class);
      }
    }
  }

  @Test
  void rejectsSkippingBackToPending() {
    assertThatThrownBy(() -> RefundStateMachine.require(RefundState.PENDING, RefundState.PENDING))
        .isInstanceOf(IllegalStateException.class);
  }
}
