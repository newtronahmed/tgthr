package com.groupcollections.refund;

public interface RefundProvider {

  RefundResult refund(Refund refund);

  RefundResult verify(Refund refund);

  record RefundResult(Status status, String providerReference, String failureCode) {

    public enum Status {
      SUCCESS,
      PENDING,
      FAILED
    }

    public static RefundResult success(String providerReference) {
      return new RefundResult(Status.SUCCESS, providerReference, null);
    }

    public static RefundResult pending(String providerReference) {
      return new RefundResult(Status.PENDING, providerReference, null);
    }

    public static RefundResult failed(String failureCode) {
      return new RefundResult(Status.FAILED, null, failureCode);
    }
  }
}
