package com.groupcollections.payments;

/**
 * The final status of a charge lookup; UNKNOWN leaves the contribution pending for the next run.
 */
public enum PaymentVerification {
  PAID,
  FAILED,
  NO_PAYMENT,
  UNKNOWN
}
