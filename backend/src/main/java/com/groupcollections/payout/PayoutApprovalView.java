package com.groupcollections.payout;

import java.time.Instant;
import java.util.UUID;

/** Public approver page data; never contains either phone number. */
public record PayoutApprovalView(
    UUID payoutId,
    String goalTitle,
    String approverName,
    long amountPesewas,
    long feePesewas,
    long netPesewas,
    String currency,
    String state,
    Instant tokenExpiresAt) {}
