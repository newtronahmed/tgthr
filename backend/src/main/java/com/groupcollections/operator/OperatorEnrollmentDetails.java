package com.groupcollections.operator;

import java.util.List;

public record OperatorEnrollmentDetails(
    String totpSecret, String otpauthUri, List<String> recoveryCodes) {

  public OperatorEnrollmentDetails {
    recoveryCodes = List.copyOf(recoveryCodes);
  }

  @Override
  public String toString() {
    return "OperatorEnrollmentDetails[totpSecret=[REDACTED], otpauthUri=[REDACTED], recoveryCodes=[REDACTED], recoveryCodeCount="
        + recoveryCodes.size()
        + "]";
  }
}
