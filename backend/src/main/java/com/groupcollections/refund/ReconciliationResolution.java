package com.groupcollections.refund;

import java.time.Instant;
import java.util.UUID;

/** One immutable resolution of a suspense item; an income proposal starts as {@code PROPOSED}. */
public record ReconciliationResolution(
    UUID id,
    UUID contributionId,
    String resolution,
    String status,
    UUID proposerId,
    UUID approverId,
    String reason,
    String idempotencyKey,
    String approvalIdempotencyKey,
    UUID postingSetId,
    Instant createdAt,
    Instant approvedAt) {}
