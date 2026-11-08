package com.groupcollections.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.ledger.Money;
import com.groupcollections.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SettlementEligibilityTest extends ApiIntegrationTest {

  @Autowired private SettlementRepository settlements;

  @Test
  void goalOwnedSettledAmountExcludesFeesAndSuspenseAndNetsReversals() {
    String slug = insertGoal("settled-balance", "OPEN", null);
    UUID goalId = jdbc.queryForObject("select id from goals where slug = ?", UUID.class, slug);
    Map<String, Object> contribution = createContribution(slug, 5_000, "0240000001").getBody();
    UUID contributionId = UUID.fromString((String) contribution.get("id"));
    long amount = ((Number) contribution.get("amountPesewas")).longValue();
    long processing = ((Number) contribution.get("processingFeePesewas")).longValue();
    long total = ((Number) contribution.get("totalPesewas")).longValue();

    Settlement credit = settlement("credit", total, processing, SettlementDirection.CREDIT);
    settlements.save(credit);
    settlements.saveItem(
        item(credit.id(), contributionId, amount, total, processing, SettlementDirection.CREDIT));
    assertThat(settlements.markSettled(credit.id(), Instant.now())).isEqualTo(1);
    assertThat(settlements.settledGoalAmount(goalId)).isEqualTo(Money.of(amount, "GHS"));

    Settlement reversal = settlement("reversal", 1_000, 0, SettlementDirection.DEBIT);
    settlements.save(reversal);
    settlements.saveItem(
        new SettlementItem(
            UUID.randomUUID(),
            reversal.id(),
            contributionId,
            SettlementItemType.REVERSAL,
            SettlementDirection.DEBIT,
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Money.of(1_000, "GHS"),
            Money.of(1_000, "GHS"),
            Instant.now()));
    assertThat(settlements.markSettled(reversal.id(), Instant.now())).isEqualTo(1);
    assertThat(settlements.settledGoalAmount(goalId)).isEqualTo(Money.of(amount - 1_000, "GHS"));

    Map<String, Object> suspense = createContribution(slug, 2_000, "0240000002").getBody();
    UUID suspenseId = UUID.fromString((String) suspense.get("id"));
    jdbc.update("update contributions set requires_reconciliation = true where id = ?", suspenseId);
    long suspenseAmount = ((Number) suspense.get("amountPesewas")).longValue();
    long suspenseProcessing = ((Number) suspense.get("processingFeePesewas")).longValue();
    long suspenseTotal = ((Number) suspense.get("totalPesewas")).longValue();
    Settlement suspended =
        settlement("suspended", suspenseTotal, suspenseProcessing, SettlementDirection.CREDIT);
    settlements.save(suspended);
    settlements.saveItem(
        item(
            suspended.id(),
            suspenseId,
            suspenseAmount,
            suspenseTotal,
            suspenseProcessing,
            SettlementDirection.CREDIT));
    assertThat(settlements.markSettled(suspended.id(), Instant.now())).isEqualTo(1);
    assertThat(settlements.settledGoalAmount(goalId)).isEqualTo(Money.of(4_000, "GHS"));

    assertThat(settlements.availableForPayout(goalId, Money.of(10_000, "GHS")))
        .isEqualTo(Money.of(4_000, "GHS"));
    assertThat(settlements.availableForPayout(goalId, Money.of(3_000, "GHS")))
        .isEqualTo(Money.of(3_000, "GHS"));
  }

  private Settlement settlement(
      String reference, long gross, long fees, SettlementDirection direction) {
    Instant now = Instant.now();
    return new Settlement(
        UUID.randomUUID(),
        SettlementProvider.PAYSTACK,
        reference,
        LocalDate.now(),
        Money.of(gross, "GHS"),
        Money.of(fees, "GHS"),
        Money.of(gross - fees, "GHS"),
        direction,
        SettlementStatus.PENDING,
        null,
        now,
        now);
  }

  private SettlementItem item(
      UUID settlementId,
      UUID contributionId,
      long goalAmount,
      long gross,
      long fee,
      SettlementDirection direction) {
    return new SettlementItem(
        UUID.randomUUID(),
        settlementId,
        contributionId,
        SettlementItemType.CONTRIBUTION,
        direction,
        Money.of(gross, "GHS"),
        Money.of(fee, "GHS"),
        Money.of(gross - fee, "GHS"),
        Money.of(goalAmount, "GHS"),
        Instant.now());
  }
}
