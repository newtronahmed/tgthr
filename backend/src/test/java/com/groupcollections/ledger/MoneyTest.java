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
    assertThatThrownBy(() -> Money.ofPesewas(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  @Criterion("INV-LEDGER-005")
  void zeroIsAllowedButNotPositive() {
    assertThat(Money.ZERO.isPositive()).isFalse();
    assertThat(Money.ofPesewas(1).isPositive()).isTrue();
  }

  @Test
  @Criterion("INV-LEDGER-005")
  void addsExactlyInPesewas() {
    assertThat(Money.ofPesewas(1_050).plus(Money.ofPesewas(250))).isEqualTo(Money.ofPesewas(1_300));
  }

  @Test
  @Criterion("INV-LEDGER-005")
  void overflowFailsLoudlyInsteadOfWrapping() {
    assertThatThrownBy(() -> Money.ofPesewas(Long.MAX_VALUE).plus(Money.ofPesewas(1)))
        .isInstanceOf(ArithmeticException.class);
  }

  @Property
  @Criterion("INV-LEDGER-005")
  void additionIsCommutativeAndNeverNegative(
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long a,
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long b) {
    Money x = Money.ofPesewas(a);
    Money y = Money.ofPesewas(b);

    assertThat(x.plus(y)).isEqualTo(y.plus(x));
    assertThat(x.plus(y).pesewas()).isGreaterThanOrEqualTo(0);
  }
}
