package com.groupcollections.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OtpVerify(
    @NotBlank @Pattern(regexp = "^(\\+233|0)[0-9]{9}$") String phone,
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
    @Size(max = 80) String displayName,
    @Size(max = 120) String legalName) {

  @Override
  public String toString() {
    return "OtpVerify[phone=redacted, code=redacted]";
  }
}
