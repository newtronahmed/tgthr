package com.groupcollections.operator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateOperatorRequest(@NotBlank String username, @NotEmpty List<String> roles) {}
