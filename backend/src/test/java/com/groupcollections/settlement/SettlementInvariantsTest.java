package com.groupcollections.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.alerts.Alert;
import com.groupcollections.ledger.Money;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.RecordingAlertPublisher;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class SettlementInvariantsTest extends ApiIntegrationTest {

  @Autowired private SettlementRepository settlements;

  @Test
  @Criterion("INV-SETTLE-002")
  void settlementItemContributionMustExistAndNullIsAllowedForAdjustments() {
    Settlement settlement = settlement("missing-contribution");
    settlements.save(settlement);

    assertThatThrownBy(
            () -> settlements.saveItem(item(settlement.id(), UUID.randomUUID(), 1_000, 0)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.SETTLEMENT_ORPHAN);

    settlements.saveItem(
        new SettlementItem(
            UUID.randomUUID(),
            settlement.id(),
            null,
            SettlementItemType.ADJUSTMENT,
            SettlementDirection.CREDIT,
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Instant.now()));
    assertThat(settlements.findByContributionId(null)).isEmpty();
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.SETTLEMENT_UNMATCHED);
  }

  @Test
  @Criterion("INV-SETTLE-003")
  void markSettledRejectsMismatchedItemTotalsAndAcceptsMatchingOnes() {
    Settlement mismatched = settlement("mismatch");
    settlements.save(mismatched);
    settlements.saveItem(item(mismatched.id(), null, 4_000, 0));
    assertThatThrownBy(() -> settlements.markSettled(mismatched.id(), Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("totals");
    assertThat(settlements.findById(mismatched.id()).orElseThrow().status())
        .isEqualTo(SettlementStatus.PENDING);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.SETTLEMENT_TOTAL_MISMATCH);

    Settlement matched = settlement("matched");
    settlements.save(matched);
    settlements.saveItem(item(matched.id(), null, 5_000, 100));
    assertThat(settlements.markSettled(matched.id(), Instant.now())).isEqualTo(1);
    assertThat(settlements.findById(matched.id()).orElseThrow().status())
        .isEqualTo(SettlementStatus.SETTLED);
  }

  @Test
  @Criterion("INV-SETTLE-004")
  void duplicateContributionRaisesAndIsRejectedButFailedBatchesCanRetry() {
    String slug = insertGoal("settlement-duplicate", "OPEN", null);
    Map<String, Object> contribution = createContribution(slug, 5_000, "0240000001").getBody();
    UUID contributionId = UUID.fromString((String) contribution.get("id"));

    Settlement first = settlement("duplicate-first");
    Settlement second = settlement("duplicate-second");
    settlements.save(first);
    settlements.save(second);
    settlements.saveItem(item(first.id(), contributionId, 5_000, 100));

    assertThatThrownBy(() -> settlements.saveItem(item(second.id(), contributionId, 5_000, 100)))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.DUPLICATE_SETTLEMENT);

    assertThat(settlements.markFailed(first.id())).isEqualTo(1);
    Settlement retry = settlement("retry");
    settlements.save(retry);
    settlements.saveItem(item(retry.id(), contributionId, 5_000, 100));
    assertThat(
            count(
                "select count(*) from settlement_items where contribution_id = ?", contributionId))
        .isEqualTo(2);
  }

  @Test
  void settlementItemsAreAppendOnlyAndOnlyPendingBatchesAcceptItems() {
    Settlement settlement = settlement("append-only");
    settlements.save(settlement);
    settlements.saveItem(item(settlement.id(), null, 5_000, 100));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update settlement_items set goal_amount_minor_units = 1 where settlement_id = ?",
                    settlement.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from settlement_items where settlement_id = ?", settlement.id()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(settlements.markSettled(settlement.id(), Instant.now())).isEqualTo(1);

    assertThatThrownBy(() -> settlements.saveItem(item(settlement.id(), null, 1_000, 0)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void providerLineReferenceIsIdempotentWithinABatch() {
    Settlement batch = settlement("line-idempotency");
    settlements.save(batch);
    SettlementItem first =
        new SettlementItem(
            UUID.randomUUID(),
            batch.id(),
            null,
            SettlementItemType.ADJUSTMENT,
            SettlementDirection.CREDIT,
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Instant.now(),
            "provider-line-1");
    settlements.saveItem(first);

    assertThatThrownBy(
            () ->
                settlements.saveItem(
                    new SettlementItem(
                        UUID.randomUUID(),
                        batch.id(),
                        null,
                        SettlementItemType.ADJUSTMENT,
                        SettlementDirection.CREDIT,
                        Money.of(1_000, "GHS"),
                        Money.of(0, "GHS"),
                        Money.of(1_000, "GHS"),
                        Money.of(0, "GHS"),
                        Instant.now(),
                        "provider-line-1")))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.DUPLICATE_SETTLEMENT);

    Settlement other = settlement("line-idempotency-other");
    settlements.save(other);
    settlements.saveItem(
        new SettlementItem(
            UUID.randomUUID(),
            other.id(),
            null,
            SettlementItemType.ADJUSTMENT,
            SettlementDirection.CREDIT,
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Money.of(1_000, "GHS"),
            Money.of(0, "GHS"),
            Instant.now(),
            "provider-line-1"));
    assertThat(
            count(
                "select count(*) from settlement_items where provider_item_reference = 'provider-line-1'"))
        .isEqualTo(2);
  }

  @Test
  void settlementStateAndCurrencyGuardsHold() {
    Settlement pending = settlement("state-guard");
    settlements.save(pending);
    assertThatThrownBy(
            () ->
                settlements.save(
                    new Settlement(
                        UUID.randomUUID(),
                        SettlementProvider.PAYSTACK,
                        "duplicate-state",
                        pending.settlementDate(),
                        pending.gross(),
                        pending.fees(),
                        pending.net(),
                        pending.direction(),
                        SettlementStatus.SETTLED,
                        Instant.now(),
                        Instant.now(),
                        Instant.now())))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(settlements.markFailed(pending.id())).isEqualTo(1);
    assertThatThrownBy(() -> settlements.markSettled(pending.id(), Instant.now()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> settlements.saveItem(item(pending.id(), null, 1_000, 0)))
        .isInstanceOf(IllegalStateException.class);

    Settlement ghs = settlement("currency-guard");
    settlements.save(ghs);
    SettlementItem ngn =
        new SettlementItem(
            UUID.randomUUID(),
            ghs.id(),
            null,
            SettlementItemType.ADJUSTMENT,
            SettlementDirection.CREDIT,
            Money.of(1_000, "NGN"),
            Money.of(0, "NGN"),
            Money.of(1_000, "NGN"),
            Money.of(0, "NGN"),
            Instant.now());
    assertThatThrownBy(() -> settlements.saveItem(ngn))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");

    Settlement duplicate = settlement("batch-duplicate");
    settlements.save(duplicate);
    assertThatThrownBy(
            () ->
                settlements.save(
                    new Settlement(
                        UUID.randomUUID(),
                        SettlementProvider.PAYSTACK,
                        "batch-duplicate",
                        duplicate.settlementDate(),
                        duplicate.gross(),
                        duplicate.fees(),
                        duplicate.net(),
                        duplicate.direction(),
                        duplicate.status(),
                        null,
                        Instant.now(),
                        Instant.now())))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.DUPLICATE_SETTLEMENT_BATCH);
  }

  private Settlement settlement(String reference) {
    Instant now = Instant.now();
    return new Settlement(
        UUID.randomUUID(),
        SettlementProvider.PAYSTACK,
        reference,
        LocalDate.now(),
        Money.of(5_000, "GHS"),
        Money.of(100, "GHS"),
        Money.of(4_900, "GHS"),
        SettlementDirection.CREDIT,
        SettlementStatus.PENDING,
        null,
        now,
        now);
  }

  private SettlementItem item(
      UUID settlementId, UUID contributionId, long grossMinorUnits, long feeMinorUnits) {
    Money gross = Money.of(grossMinorUnits, "GHS");
    Money fee = Money.of(feeMinorUnits, "GHS");
    Money net = gross.minus(fee);
    return new SettlementItem(
        UUID.randomUUID(),
        settlementId,
        contributionId,
        contributionId == null ? SettlementItemType.ADJUSTMENT : SettlementItemType.CONTRIBUTION,
        SettlementDirection.CREDIT,
        gross,
        fee,
        net,
        gross,
        Instant.now());
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }
}
