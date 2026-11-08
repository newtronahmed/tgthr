package com.groupcollections.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.groupcollections.ledger.Money;
import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Test;

class GoalRulesTest {

  @Test
  @Criterion("AC-CONTRIB-003")
  void openGoalRejectsAmountBelowMinimum() {
    GoalRules rules = new GoalRules(TargetType.OPEN, null, Money.of(1_000, "GHS"));

    assertThatCode(() -> rules.check(Money.of(1_000, "GHS"))).doesNotThrowAnyException();

    AmountValidationException thrown =
        catchThrowableOfType(
            AmountValidationException.class, () -> rules.check(Money.of(999, "GHS")));

    assertThat(thrown.code()).isEqualTo("AMOUNT_TOO_LOW");
  }
}
