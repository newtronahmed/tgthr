package com.groupcollections.refund;

public interface RefundProvider {

  RefundResult refund(Refund refund);

  RefundResult verify(Refund refund);

  record RefundResult(
      Status status, String providerReference, String failureCode, PendingReason pendingReason) {

    public enum Status {
      SUCCESS,
      PENDING,
      FAILED
    }

    /**
     * Why an outcome is not yet knowable. Persisted on the refund event so operators can triage
     * timeouts, duplicates, rate limits and mismatched provider data.
     */
    public enum PendingReason {
      TIMEOUT,
      CONFLICT,
      RATE_LIMITED,
      UNKNOWN_STATUS,
      AMOUNT_MISMATCH,
      CURRENCY_MISMATCH
    }

    public static RefundResult success(String providerReference) {
      return new RefundResult(Status.SUCCESS, providerReference, null, null);
    }

    public static RefundResult pending(String providerReference) {
      return pending(providerReference, PendingReason.UNKNOWN_STATUS);
    }

    public static RefundResult pending(String providerReference, PendingReason reason) {
      return new RefundResult(Status.PENDING, providerReference, null, reason);
    }

    public static RefundResult failed(String failureCode) {
      return new RefundResult(Status.FAILED, null, failureCode, null);
    }
  }
}
