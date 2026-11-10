package com.groupcollections.payout;

import java.time.Instant;
import java.util.UUID;

/** Public payout visibility; never contains a contributor, approver or destination phone. */
public record PayoutPublicView(
    UUID id,
    long amountPesewas,
    long feePesewas,
    long netPesewas,
    String currency,
    String state,
    Instant holdExpiresAt,
    Instant completedAt) {}
