package com.groupcollections.refund;

import jakarta.validation.constraints.NotBlank;

public record ReconciliationResolutionRequest(@NotBlank String reason) {}
