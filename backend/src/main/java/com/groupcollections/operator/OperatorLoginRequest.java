package com.groupcollections.operator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OperatorLoginRequest(
    @NotBlank @Size(min = 3, max = 64) String username,
    @NotBlank @Size(min = 12, max = 1_024) String password) {}
