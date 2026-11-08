package com.groupcollections.alerts;

public record Alert(AlertType type, String reference, String detail) {

  public enum AlertType {
    UNKNOWN_REFERENCE,
    AMOUNT_MISMATCH,
    LATE_SUCCESS,
    VERIFY_MISMATCH,
    STUCK_PENDING,
    SMS_FAILURE,
    DESTINATION_UNVERIFIED,
    DESTINATION_UNAVAILABLE,
    RETENTION_FAILURE
  }
}
