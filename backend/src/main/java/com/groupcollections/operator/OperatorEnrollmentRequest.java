package com.groupcollections.operator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OperatorEnrollmentRequest(
    @NotBlank String token, @NotBlank @Size(min = 12, max = 1_024) String password) {}
