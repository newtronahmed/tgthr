package com.groupcollections.contribution;

/** Events that can move a contribution; the state machine is the only authority on their effect. */
public enum ContributionEvent {
  PROMPT_SENT,
  PROMPT_REJECTED,
  PAYMENT_SUCCEEDED,
  PAYMENT_FAILED,
  PAYMENT_TIMED_OUT,
  REFUND_APPROVED
}
