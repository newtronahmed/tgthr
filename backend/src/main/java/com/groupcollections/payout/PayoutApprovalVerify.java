package com.groupcollections.payout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PayoutApprovalVerify(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {}
