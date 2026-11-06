package com.groupcollections.goal;

import com.groupcollections.ledger.Money;
import java.time.Instant;
import java.util.UUID;

/** The goal fields a contribution needs to apply the amount policy and the acceptance rule. */
public record Goal(
    UUID id,
    TargetType targetType,
    Money fixedAmount,
    Money minimumAmount,
    GoalStatus status,
    Instant deadline) {

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
