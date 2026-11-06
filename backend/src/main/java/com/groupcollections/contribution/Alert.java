package com.groupcollections.contribution;

public record Alert(AlertType type, String reference, String detail) {

  public enum AlertType {
    UNKNOWN_REFERENCE,
    AMOUNT_MISMATCH,
    LATE_SUCCESS,
    VERIFY_MISMATCH
  }
}
