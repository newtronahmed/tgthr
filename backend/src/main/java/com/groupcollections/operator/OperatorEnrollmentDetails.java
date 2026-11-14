package com.groupcollections.operator;

import java.util.List;

public record OperatorEnrollmentDetails(
    String totpSecret, String otpauthUri, List<String> recoveryCodes) {

  public OperatorEnrollmentDetails {
    recoveryCodes = List.copyOf(recoveryCodes);
  }
}
