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

  @Property
  @Criterion("AC-CONTRIB-014")
  void totalEqualsAmountPlusFeesAndPostingSetNetsToZero(
      @ForAll @LongRange(min = 1_000, max = 10_000_000) long amountPesewas) {
    Money amount = Money.ofPesewas(amountPesewas);
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
