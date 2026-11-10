package com.groupcollections.contribution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.groupcollections.payout.PayoutPublicView;
import java.time.Instant;
import java.util.List;

/** The public goal page body; it never carries payer phone numbers. */
public record PublicGoalView(
    String slug,
    String title,
    String template,
    String targetType,
    Long fixedAmountPesewas,
    Long targetAmountPesewas,
    long minimumAmountPesewas,
    long raisedPesewas,
    long contributorCount,
    Instant deadline,
    String status,
    PayoutRules payoutRules,
    List<PayoutPublicView> payouts,
    List<FeedItem> feed) {

  public record PayoutRules(
      String destinationType,
      String secondApproverName,
      String secondApprover,
      long secondApproverThresholdPesewas,
      int holdWindowMinutes,
      String surplusRule) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FeedItem(String displayName, boolean anonymous, long amountPesewas, Instant at) {}
}
