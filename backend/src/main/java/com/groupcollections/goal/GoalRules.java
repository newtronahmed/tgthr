package com.groupcollections.goal;

import com.groupcollections.ledger.Money;
import java.util.Objects;

/**
 * The amount policy for a goal (SPEC FEAT-001 rule 2): a fixed goal accepts only its fixed amount;
 * an open goal accepts any amount at or above its minimum, GHS 10 (1,000 pesewas) by default.
 */
public final class GoalRules {

  private final TargetType targetType;
  private final Money fixedAmount;
  private final Money minimumAmount;

  public GoalRules(TargetType targetType, Money fixedAmount, Money minimumAmount) {
    this.targetType = Objects.requireNonNull(targetType, "targetType");
    this.fixedAmount = fixedAmount;
    this.minimumAmount = minimumAmount;
  }

  public void check(Money amount) {
    if (targetType == TargetType.FIXED) {
      requireSameCurrency(amount, fixedAmount);
      if (!amount.equals(fixedAmount)) {
        throw new AmountValidationException(
            "AMOUNT_NOT_ALLOWED", "a fixed goal accepts only its fixed amount");
      }
    } else {
      requireSameCurrency(amount, minimumAmount);
      if (amount.minorUnits() < minimumAmount.minorUnits()) {
        throw new AmountValidationException(
            "AMOUNT_TOO_LOW",
            "amount must be at least " + minimumAmount.minorUnits() + " minor units");
      }
    }
  }

  private static void requireSameCurrency(Money amount, Money goalAmount) {
    if (!amount.currencyCode().equals(goalAmount.currencyCode())) {
      throw new AmountValidationException(
          "AMOUNT_NOT_ALLOWED", "contribution currency must match the goal currency");
    }
  }
}
