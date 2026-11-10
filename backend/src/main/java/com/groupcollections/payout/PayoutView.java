package com.groupcollections.payout;

import java.time.Instant;
import java.util.UUID;

/** Organizer-facing payout state; never contains a phone number. */
public record PayoutView(
    UUID id,
    UUID goalId,
    long amountPesewas,
    long feePesewas,
    long netPesewas,
    String currency,
    String state,
    Instant createdAt) {}
