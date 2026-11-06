package com.groupcollections.contribution;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    long raisedPesewas,
    long contributorCount,
    Instant deadline,
    String status,
    PayoutRules payoutRules,
    List<FeedItem> feed) {

  public record PayoutRules(
      String destinationType, String secondApprover, int holdWindowMinutes, String surplusRule) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FeedItem(String displayName, boolean anonymous, long amountPesewas, Instant at) {}
}
