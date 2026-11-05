package com.groupcollections.goal;

import com.groupcollections.ledger.Money;
import java.util.UUID;

/** The goal fields a contribution needs to apply the amount policy. */
public record Goal(UUID id, TargetType targetType, Money fixedAmount, Money minimumAmount) {

  public GoalRules rules() {
    return new GoalRules(targetType, fixedAmount, minimumAmount);
  }
}
