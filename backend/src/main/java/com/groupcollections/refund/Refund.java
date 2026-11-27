package com.groupcollections.refund;

import java.time.Instant;
import java.util.UUID;

public record Refund(
    UUID id,
    UUID contributionId,
    UUID goalId,
    String sourceAccount,
    long amountMinorUnits,
    long platformFeeMinorUnits,
    long processingFeeMinorUnits,
    long totalMinorUnits,
    String currencyCode,
    RefundState state,
    String idempotencyKey,
    String providerTransactionReference,
    String providerReference,
    String clientReference,
    UUID operatorId,
    String reason,
    String failureCode,
    Instant providerRequestedAt,
    Instant succeededAt,
    Instant failedAt,
    Instant createdAt,
    Instant updatedAt) {}
