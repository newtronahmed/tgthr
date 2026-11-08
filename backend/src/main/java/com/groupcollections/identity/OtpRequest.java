package com.groupcollections.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpRequest(@NotBlank @Pattern(regexp = "^(\\+233|0)[0-9]{9}$") String phone) {

  @Override
  public String toString() {
    return "OtpRequest[phone=redacted]";
  }
}
