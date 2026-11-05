package com.groupcollections.contribution;

import com.groupcollections.payments.Network;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateContributionRequest(
    @NotNull @Min(1) Long amountPesewas,
    @NotBlank @Pattern(regexp = "^0[0-9]{9}$") String momoNumber,
    @NotNull Network network,
    @Size(max = 80) String displayName,
    Boolean anonymous) {

  public boolean anonymousOrFalse() {
    return Boolean.TRUE.equals(anonymous);
  }
}
