package com.groupcollections.payout;

/** Provider transfer operation; the real Paystack adapter is added after the T301 spike. */
public interface PayoutTransferProvider {

  TransferResult transfer(Payout payout);

  record TransferResult(Status status, String providerReference, String failureCode) {
    public enum Status {
      SUCCESS,
      FAILED,
      UNKNOWN
    }
  }
}
