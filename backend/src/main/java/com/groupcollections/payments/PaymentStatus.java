package com.groupcollections.payments;

/** The partner status lookup result; UNKNOWN means a retryable failure. */
public record PaymentStatus(PaymentVerification outcome, long amountPesewas, String currency) {

  public static PaymentStatus unknown() {
    return new PaymentStatus(PaymentVerification.UNKNOWN, 0, null);
  }
}
