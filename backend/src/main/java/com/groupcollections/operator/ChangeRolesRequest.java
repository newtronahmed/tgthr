package com.groupcollections.operator;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ChangeRolesRequest(@NotEmpty List<String> roles) {}
