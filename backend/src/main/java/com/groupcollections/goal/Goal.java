package com.groupcollections.goal;

import com.groupcollections.ledger.Money;
import java.time.Instant;
import java.util.UUID;

/** The goal fields the public page and contribution policy need. */
public record Goal(
    UUID id,
    String slug,
    String title,
    String template,
    TargetType targetType,
    Money fixedAmount,
    Money targetAmount,
    Money minimumAmount,
    GoalStatus status,
    Instant deadline,
    String destinationType,
    String secondApprover,
    long secondApproverThresholdPesewas,
    int holdWindowMinutes,
    String surplusRule) {

  public GoalRules rules() {
    return new GoalRules(targetType, fixedAmount, minimumAmount);
  }

  /**
   * SPEC rule 4: a goal accepts contributions only while open, unfrozen and before its deadline.
   */
  public boolean acceptsContributions(Instant now) {
    return status == GoalStatus.OPEN && (deadline == null || now.isBefore(deadline));
  }
}
