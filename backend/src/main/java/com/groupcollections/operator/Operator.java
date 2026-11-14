package com.groupcollections.operator;

import java.time.Instant;
import java.util.UUID;

public record Operator(
    UUID id,
    String username,
    String passwordHash,
    byte[] totpSecretCiphertext,
    OperatorStatus status,
    int failedAttempts,
    Instant lockedUntil,
    long lastTotpStep) {}
