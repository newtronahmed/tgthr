package com.groupcollections.operator;

import java.util.List;
import java.util.UUID;

public record OperatorAdminView(
    UUID id, String username, OperatorStatus status, List<String> roles) {}
