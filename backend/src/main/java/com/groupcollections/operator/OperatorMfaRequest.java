package com.groupcollections.operator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OperatorMfaRequest(
    @NotBlank String challengeToken, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {}
