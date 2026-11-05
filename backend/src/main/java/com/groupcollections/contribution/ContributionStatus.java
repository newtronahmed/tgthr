package com.groupcollections.contribution;

import java.util.UUID;

/** The public view of a contribution; it never carries the payer's phone number. */
public record ContributionStatus(
    UUID id,
    ContributionState state,
    long amountPesewas,
    long processingFeePesewas,
    long platformFeePesewas,
    long feePesewas,
    long totalPesewas) {}
