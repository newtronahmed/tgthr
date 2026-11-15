package com.groupcollections.refund;

import java.time.Instant;
import java.util.UUID;

public record RefundView(
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
    String providerReference,
    UUID operatorId,
    String reason,
    String failureCode,
    Instant createdAt,
    Instant succeededAt,
    Instant failedAt) {

  public static RefundView from(Refund refund) {
    return new RefundView(
        refund.id(),
        refund.contributionId(),
        refund.goalId(),
        refund.sourceAccount(),
        refund.amountMinorUnits(),
        refund.platformFeeMinorUnits(),
        refund.processingFeeMinorUnits(),
        refund.totalMinorUnits(),
        refund.currencyCode(),
        refund.state(),
        refund.providerReference(),
        refund.operatorId(),
        refund.reason(),
        refund.failureCode(),
        refund.createdAt(),
        refund.succeededAt(),
        refund.failedAt());
  }
}
