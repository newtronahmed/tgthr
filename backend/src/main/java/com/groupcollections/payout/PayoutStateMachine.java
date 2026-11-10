package com.groupcollections.payout;

import java.util.EnumMap;
import java.util.Map;

/**
 * FEAT-002 payout state machine. The table is the only legal set of transitions; the worker's
 * freeze guard applies to READY -> EXECUTING only. See specs/domain/payout-state-machine.md.
 */
public final class PayoutStateMachine {

  private static final Map<PayoutState, Map<PayoutEvent, PayoutState>> TRANSITIONS =
      new EnumMap<>(PayoutState.class);

  static {
    allow(PayoutState.REQUESTED, PayoutEvent.APPROVE, PayoutState.APPROVED);
    allow(PayoutState.REQUESTED, PayoutEvent.DECLINE, PayoutState.CANCELLED);
    allow(PayoutState.REQUESTED, PayoutEvent.CANCEL, PayoutState.CANCELLED);
    allow(PayoutState.REQUESTED, PayoutEvent.APPROVAL_EXPIRED, PayoutState.CANCELLED);
    allow(PayoutState.REQUESTED, PayoutEvent.APPROVAL_EXHAUSTED, PayoutState.CANCELLED);
    allow(PayoutState.APPROVED, PayoutEvent.START_HOLD, PayoutState.HOLD);
    allow(PayoutState.APPROVED, PayoutEvent.CANCEL, PayoutState.CANCELLED);
    allow(PayoutState.HOLD, PayoutEvent.HOLD_EXPIRED, PayoutState.READY);
    allow(PayoutState.HOLD, PayoutEvent.CANCEL, PayoutState.CANCELLED);
    allow(PayoutState.READY, PayoutEvent.CLAIM, PayoutState.EXECUTING);
    allow(PayoutState.READY, PayoutEvent.FEE_MISMATCH, PayoutState.FAILED);
    allow(PayoutState.READY, PayoutEvent.CANCEL, PayoutState.CANCELLED);
    allow(PayoutState.EXECUTING, PayoutEvent.PROVIDER_SUCCEEDED, PayoutState.COMPLETED);
    allow(PayoutState.EXECUTING, PayoutEvent.PROVIDER_FAILED, PayoutState.FAILED);
    allow(PayoutState.EXECUTING, PayoutEvent.PROVIDER_UNKNOWN, PayoutState.EXECUTING);
  }

  private PayoutStateMachine() {}

  private static void allow(PayoutState from, PayoutEvent event, PayoutState to) {
    TRANSITIONS.computeIfAbsent(from, ignored -> new EnumMap<>(PayoutEvent.class)).put(event, to);
  }

  public static boolean canTransition(PayoutState from, PayoutEvent event, boolean frozen) {
    if (from == PayoutState.READY && event == PayoutEvent.CLAIM && frozen) {
      return false;
    }
    return TRANSITIONS.getOrDefault(from, Map.of()).containsKey(event);
  }

  public static PayoutState transition(PayoutState from, PayoutEvent event, boolean frozen) {
    if (!canTransition(from, event, frozen)) {
      throw new IllegalStateException("illegal payout transition " + from + " -> " + event);
    }
    return TRANSITIONS.get(from).get(event);
  }

  public enum PayoutState {
    REQUESTED,
    APPROVED,
    HOLD,
    READY,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  public enum PayoutEvent {
    APPROVE,
    DECLINE,
    CANCEL,
    APPROVAL_EXPIRED,
    APPROVAL_EXHAUSTED,
    START_HOLD,
    HOLD_EXPIRED,
    CLAIM,
    FEE_MISMATCH,
    PROVIDER_SUCCEEDED,
    PROVIDER_FAILED,
    PROVIDER_UNKNOWN
  }
}
