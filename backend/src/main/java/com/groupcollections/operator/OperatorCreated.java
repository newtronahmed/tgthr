package com.groupcollections.operator;

import java.time.Instant;
import java.util.UUID;

public record OperatorCreated(
    UUID id, String username, String enrollmentToken, Instant expiresAt) {}
