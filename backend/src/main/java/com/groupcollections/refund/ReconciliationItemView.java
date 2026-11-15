package com.groupcollections.refund;

import java.time.Instant;
import java.util.UUID;

/** One unresolved suspense item. Deliberately carries no payer contact details (AC-OPS-001). */
public record ReconciliationItemView(
    UUID contributionId,
    UUID goalId,
    String goalTitle,
    long amountMinorUnits,
    String currencyCode,
    String providerReference,
    Instant suspendedAt,
    long ageSeconds) {}
