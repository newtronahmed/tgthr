package com.groupcollections.refund;

/** The single refund transition table; terminal states never move again. */
public final class RefundStateMachine {

  private RefundStateMachine() {}

  public static boolean canTransition(RefundState from, RefundState to) {
    return switch (from) {
      case PENDING ->
          to == RefundState.PROVIDER_PENDING
              || to == RefundState.SUCCEEDED
              || to == RefundState.FAILED;
      case PROVIDER_PENDING -> to == RefundState.SUCCEEDED || to == RefundState.FAILED;
      case SUCCEEDED, FAILED -> false;
    };
  }

  public static void require(RefundState from, RefundState to) {
    if (!canTransition(from, to)) {
      throw new IllegalStateException("illegal refund transition " + from + " -> " + to);
    }
  }
}
