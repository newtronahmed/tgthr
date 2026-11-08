package com.groupcollections.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.support.Criterion;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  @Criterion("INV-LEDGER-005")
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> Money.of(-1, "GHS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  @Criterion("INV-LEDGER-005")
  void zeroIsAllowedButNotPositive() {
    assertThat(Money.ZERO.isPositive()).isFalse();
    assertThat(Money.of(1, "GHS").isPositive()).isTrue();
  }

  @Test
  @Criterion("INV-LEDGER-005")
  void addsExactlyInPesewas() {
    assertThat(Money.of(1_050, "GHS").plus(Money.of(250, "GHS"))).isEqualTo(Money.of(1_300, "GHS"));
  }

  @Test
  @Criterion("INV-LEDGER-005")
  void overflowFailsLoudlyInsteadOfWrapping() {
    assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "GHS").plus(Money.of(1, "GHS")))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @Criterion("INV-LEDGER-007")
  void validatesAndRecordsCurrency() {
    assertThat(Money.of(100, "GHS").currencyCode()).isEqualTo("GHS");
    assertThatThrownBy(() -> Money.of(1, "ghs")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.of(1, "GH")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Criterion("INV-LEDGER-007")
  void plusRejectsDifferentCurrencies() {
    assertThatThrownBy(() -> Money.of(100, "GHS").plus(Money.of(100, "NGN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency mismatch");
  }

  @Property
  @Criterion("INV-LEDGER-007")
  void crossCurrencyAdditionAlwaysFails(
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long a,
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long b) {
    assertThatThrownBy(() -> Money.of(a, "GHS").plus(Money.of(b, "NGN")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  @Criterion("INV-LEDGER-005")
  void additionIsCommutativeAndNeverNegative(
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long a,
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long b) {
    Money x = Money.of(a, "GHS");
    Money y = Money.of(b, "GHS");

    assertThat(x.plus(y)).isEqualTo(y.plus(x));
    assertThat(x.plus(y).minorUnits()).isGreaterThanOrEqualTo(0);
  }
}
