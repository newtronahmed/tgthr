package com.groupcollections.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.ledger.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class SettlementTest {

  @Test
  void netMustEqualGrossMinusFees() {
    assertThatThrownBy(
            () ->
                new Settlement(
                    UUID.randomUUID(),
                    SettlementProvider.PAYSTACK,
                    "ref",
                    LocalDate.now(),
                    Money.of(5_000, "GHS"),
                    Money.of(100, "GHS"),
                    Money.of(4_999, "GHS"),
                    SettlementDirection.CREDIT,
                    SettlementStatus.PENDING,
                    null,
                    Instant.now(),
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gross minus fees");
  }

  @Test
  void settlementItemAllowsANullContributionOnlyForAdjustments() {
    SettlementItem item =
        new SettlementItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            SettlementItemType.ADJUSTMENT,
            SettlementDirection.CREDIT,
            Money.of(1_000, "GHS"),
            Money.of(100, "GHS"),
            Money.of(900, "GHS"),
            Money.of(0, "GHS"),
            Instant.now());
    assertThat(item.contributionId()).isNull();

    assertThatThrownBy(
            () ->
                new SettlementItem(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    SettlementItemType.CONTRIBUTION,
                    SettlementDirection.CREDIT,
                    Money.of(1_000, "GHS"),
                    Money.of(0, "GHS"),
                    Money.of(1_000, "GHS"),
                    Money.of(1_000, "GHS"),
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Property
  void netIsAlwaysGrossMinusFees(
      @ForAll @LongRange(min = 1, max = 1_000_000_000L) long gross,
      @ForAll @LongRange(min = 0, max = 1_000_000_000L) long fees) {
    if (fees >= gross) {
      return;
    }
    Settlement settlement =
        new Settlement(
            UUID.randomUUID(),
            SettlementProvider.PAYSTACK,
            "ref",
            LocalDate.now(),
            Money.of(gross, "GHS"),
            Money.of(fees, "GHS"),
            Money.of(gross - fees, "GHS"),
            SettlementDirection.CREDIT,
            SettlementStatus.PENDING,
            null,
            Instant.now(),
            Instant.now());
    assertThat(settlement.net()).isEqualTo(Money.of(gross - fees, "GHS"));
  }
}
