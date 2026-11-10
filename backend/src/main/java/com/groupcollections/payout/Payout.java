package com.groupcollections.payout;

import java.time.Instant;
import java.util.UUID;

/** A payout row; the state machine and ledger are authoritative for money movement. */
public record Payout(
    UUID id,
    UUID goalId,
    UUID requestedBy,
    long amountPesewas,
    long feePesewas,
    long netPesewas,
    String currency,
    String state,
    String idempotencyKey,
    String providerReference,
    Instant createdAt) {}
