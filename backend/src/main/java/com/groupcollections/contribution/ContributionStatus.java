package com.groupcollections.contribution;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** The public view of a contribution; it never carries the payer's phone number. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContributionStatus(
    UUID id,
    ContributionState state,
    long amountPesewas,
    long processingFeePesewas,
    long platformFeePesewas,
    long feePesewas,
    long totalPesewas,
    Receipt receipt) {

  public record Receipt(String reference, Instant confirmedAt) {}
}
