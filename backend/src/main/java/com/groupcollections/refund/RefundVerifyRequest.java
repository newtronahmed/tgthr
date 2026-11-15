package com.groupcollections.refund;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundVerifyRequest(@NotBlank @Size(max = 500) String reason) {}
