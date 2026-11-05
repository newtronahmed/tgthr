package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.ledger.Money;
import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

  private final FeeCalculator calculator = new FeeCalculator(150, 195);

  @Test
  @Criterion("AC-CONTRIB-017")
  void platformFeeIsOneAndAHalfPercentRoundedUpOnTop() {
    FeeBreakdown tenCedis = calculator.calculate(Money.ofPesewas(1_000));

    assertThat(tenCedis.amount()).isEqualTo(Money.ofPesewas(1_000));
    assertThat(tenCedis.platformFee()).isEqualTo(Money.ofPesewas(15));
    assertThat(tenCedis.processingFee()).isEqualTo(Money.ofPesewas(21));
    assertThat(tenCedis.total()).isEqualTo(Money.ofPesewas(1_036));

    FeeBreakdown roundedUp = calculator.calculate(Money.ofPesewas(1_001));

    assertThat(roundedUp.amount()).isEqualTo(Money.ofPesewas(1_001));
    assertThat(roundedUp.platformFee()).isEqualTo(Money.ofPesewas(16));
    assertThat(roundedUp.total()).isEqualTo(Money.ofPesewas(1_038));
  }
}
