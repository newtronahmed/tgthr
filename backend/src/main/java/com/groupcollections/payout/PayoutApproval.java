package com.groupcollections.payout;

import java.time.Instant;
import java.util.UUID;

/** A hashed approval challenge; the raw token and code never enter the database. */
public record PayoutApproval(
    UUID id,
    UUID payoutId,
    byte[] tokenHash,
    byte[] codeHash,
    Instant tokenExpiresAt,
    Instant codeExpiresAt,
    int attempts,
    int maxAttempts,
    Instant consumedAt,
    Instant createdAt) {}
