package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.ledger.ContributionPosting;
import com.groupcollections.ledger.LedgerAccount;
import com.groupcollections.ledger.LedgerDirection;
import com.groupcollections.ledger.LedgerEntry;
import com.groupcollections.ledger.Money;
import com.groupcollections.ledger.PostingSet;
import com.groupcollections.support.Criterion;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

  private static final UUID CONTRIBUTION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final FeeCalculator calculator = new FeeCalculator(150, 195);

  @Test
  @Criterion("AC-CONTRIB-017")
  void platformFeeIsOneAndAHalfPercentRoundedUpOnTop() {
    FeeBreakdown tenCedis = calculator.calculate(Money.of(1_000, "GHS"));

    assertThat(tenCedis.amount()).isEqualTo(Money.of(1_000, "GHS"));
    assertThat(tenCedis.platformFee()).isEqualTo(Money.of(15, "GHS"));
    assertThat(tenCedis.processingFee()).isEqualTo(Money.of(21, "GHS"));
    assertThat(tenCedis.total()).isEqualTo(Money.of(1_036, "GHS"));

    FeeBreakdown roundedUp = calculator.calculate(Money.of(1_001, "GHS"));

    assertThat(roundedUp.amount()).isEqualTo(Money.of(1_001, "GHS"));
    assertThat(roundedUp.platformFee()).isEqualTo(Money.of(16, "GHS"));
    assertThat(roundedUp.total()).isEqualTo(Money.of(1_038, "GHS"));
  }

  @Property
  @Criterion("AC-CONTRIB-014")
  void totalEqualsAmountPlusFeesAndPostingSetNetsToZero(
      @ForAll @LongRange(min = 1_000, max = 10_000_000) long amountPesewas) {
    Money amount = Money.of(amountPesewas, "GHS");
    FeeBreakdown fees = calculator.calculate(amount);

    assertThat(fees.total()).isEqualTo(amount.plus(fees.processingFee()).plus(fees.platformFee()));

    PostingSet posting =
        ContributionPosting.confirmation(
            CONTRIBUTION_ID, fees.amount(), fees.processingFee(), fees.platformFee());

    long net = posting.entries().stream().mapToLong(LedgerEntry::signedPesewas).sum();
    assertThat(net).isZero();

    LedgerEntry goalCredit =
        posting.entries().stream()
            .filter(entry -> entry.account() == LedgerAccount.GOAL_POOL)
            .findFirst()
            .orElseThrow();
    assertThat(goalCredit.direction()).isEqualTo(LedgerDirection.CREDIT);
    assertThat(goalCredit.amount()).isEqualTo(amount);
  }
}
