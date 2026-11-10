package com.groupcollections.settlement;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.ledger.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class SettlementRepository {

  private static final String SETTLEMENT_COLUMNS =
      """
      select id, provider, provider_settlement_reference, settlement_date,
             gross_amount_minor_units, fees_minor_units, net_amount_minor_units,
             currency_code, direction, status, received_at, created_at, updated_at
      from settlements
      """;

  private static final String ITEM_COLUMNS =
      """
      select id, settlement_id, contribution_id, item_type, direction, gross_minor_units,
             fee_minor_units, net_minor_units, goal_amount_minor_units, currency_code,
             provider_item_reference, created_at
      from settlement_items
      """;

  private final JdbcTemplate jdbc;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;

  public SettlementRepository(JdbcTemplate jdbc, AlertPublisher alerts, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.meters = meters;
  }

  public void save(Settlement settlement) {
    if (settlement.status() != SettlementStatus.PENDING) {
      throw new IllegalArgumentException("settlements must be created PENDING");
    }
    try {
      insert(settlement);
    } catch (DuplicateKeyException duplicate) {
      meters.counter("settlements.duplicate_batch").increment();
      publishAfterCompletion(
          () ->
              alerts.publish(
                  new Alert(
                      Alert.AlertType.DUPLICATE_SETTLEMENT_BATCH,
                      settlement.provider() + ":" + settlement.providerSettlementReference(),
                      "settlement batch reference already exists")));
      throw duplicate;
    }
  }

  private void insert(Settlement settlement) {
    jdbc.update(
        """
        insert into settlements
            (id, provider, provider_settlement_reference, settlement_date,
             gross_amount_minor_units, currency_code, fees_minor_units, net_amount_minor_units,
             direction, status, received_at, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        settlement.id(),
        settlement.provider().name(),
        settlement.providerSettlementReference(),
        settlement.settlementDate(),
        settlement.gross().minorUnits(),
        settlement.gross().currencyCode(),
        settlement.fees().minorUnits(),
        settlement.net().minorUnits(),
        settlement.direction().name(),
        settlement.status().name(),
        timestamp(settlement.receivedAt()),
        timestamp(settlement.createdAt()),
        timestamp(settlement.updatedAt()));
  }

  public Optional<Settlement> findById(UUID id) {
    return jdbc.query(SETTLEMENT_COLUMNS + " where id = ?", this::mapSettlement, id).stream()
        .findFirst();
  }

  public Optional<Settlement> findByProviderReference(
      SettlementProvider provider, String providerSettlementReference) {
    return jdbc
        .query(
            SETTLEMENT_COLUMNS + " where provider = ? and provider_settlement_reference = ?",
            this::mapSettlement,
            provider.name(),
            providerSettlementReference)
        .stream()
        .findFirst();
  }

  public List<Settlement> findPendingBatches() {
    return jdbc.query(
        SETTLEMENT_COLUMNS + " where status = 'PENDING' order by settlement_date",
        this::mapSettlement);
  }

  public Optional<SettlementItem> findByContributionId(UUID contributionId) {
    return jdbc
        .query(ITEM_COLUMNS + " where contribution_id = ?", this::mapItem, contributionId)
        .stream()
        .findFirst();
  }

  public void saveItem(SettlementItem item) {
    Settlement parent =
        findById(item.settlementId())
            .orElseThrow(() -> new IllegalArgumentException("settlement not found"));
    if (parent.status() != SettlementStatus.PENDING) {
      throw new IllegalStateException("settlement is not pending");
    }
    if (!parent.gross().currencyCode().equals(item.gross().currencyCode())) {
      throw new IllegalArgumentException("settlement item currency must match its batch");
    }
    if (item.contributionId() == null) {
      alerts.publish(
          new Alert(
              Alert.AlertType.SETTLEMENT_UNMATCHED,
              item.settlementId().toString(),
              "settlement item has no contribution"));
    }
    try {
      jdbc.update(
          """
          insert into settlement_items
              (id, settlement_id, contribution_id, item_type, direction, gross_minor_units,
               currency_code, fee_minor_units, net_minor_units, goal_amount_minor_units,
               provider_item_reference, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          item.id(),
          item.settlementId(),
          item.contributionId(),
          item.itemType().name(),
          item.direction().name(),
          item.gross().minorUnits(),
          item.gross().currencyCode(),
          item.fee().minorUnits(),
          item.net().minorUnits(),
          item.goalAmount().minorUnits(),
          item.providerItemReference(),
          timestamp(item.createdAt()));
    } catch (DuplicateKeyException duplicate) {
      meters.counter("settlements.duplicate").increment();
      publishAfterCompletion(
          () ->
              alerts.publish(
                  new Alert(
                      Alert.AlertType.DUPLICATE_SETTLEMENT,
                      item.contributionId() == null
                          ? item.settlementId().toString()
                          : item.contributionId().toString(),
                      "duplicate settlement line")));
      throw duplicate;
    } catch (DataIntegrityViolationException orphan) {
      meters.counter("settlements.orphan").increment();
      publishAfterCompletion(
          () ->
              alerts.publish(
                  new Alert(
                      Alert.AlertType.SETTLEMENT_ORPHAN,
                      item.contributionId() == null
                          ? item.settlementId().toString()
                          : item.contributionId().toString(),
                      "settlement item references a missing contribution")));
      throw orphan;
    }
  }

  public int markSettled(UUID settlementId, Instant receivedAt) {
    Settlement settlement =
        findById(settlementId)
            .orElseThrow(() -> new IllegalArgumentException("settlement not found"));
    if (settlement.status() != SettlementStatus.PENDING) {
      throw new IllegalStateException("settlement is not pending");
    }
    Totals totals = itemTotals(settlementId);
    long sign = settlement.direction() == SettlementDirection.CREDIT ? 1 : -1;
    boolean matches =
        totals.gross() == sign * settlement.gross().minorUnits()
            && totals.fees() == sign * settlement.fees().minorUnits()
            && totals.net() == sign * settlement.net().minorUnits();
    if (!matches) {
      meters.counter("settlements.totals_mismatch").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.SETTLEMENT_TOTAL_MISMATCH,
              settlementId.toString(),
              "settlement item totals do not match the batch"));
      throw new IllegalStateException("settlement totals do not match its items");
    }
    return jdbc.update(
        """
        update settlements
        set status = 'SETTLED', received_at = ?, updated_at = ?
        where id = ? and status = 'PENDING'
        """,
        timestamp(receivedAt),
        timestamp(receivedAt),
        settlementId);
  }

  public int markFailed(UUID settlementId) {
    return jdbc.update(
        "update settlements set status = 'FAILED', updated_at = now() where id = ? and status = 'PENDING'",
        settlementId);
  }

  /** Signed goal-owned amount from SETTLED batches; fees and suspended contributions excluded. */
  public Money settledGoalAmount(UUID goalId) {
    var row =
        jdbc.queryForMap(
            """
            select g.currency_code,
                   coalesce((
                       select sum(case when i.direction = 'CREDIT'
                                       then i.goal_amount_minor_units
                                       else -i.goal_amount_minor_units end)
                       from settlement_items i
                       join settlements s on s.id = i.settlement_id
                       join contributions c on c.id = i.contribution_id
                       where c.goal_id = g.id
                         and s.status = 'SETTLED'
                         and c.requires_reconciliation = false
                   ), 0) as settled
            from goals g where g.id = ?
            """,
            goalId);
    long settled = Math.max(0, ((Number) row.get("settled")).longValue());
    return Money.of(settled, (String) row.get("currency_code"));
  }

  /**
   * Raw min(settled, goal pool) cap. Callers must subtract completed payouts and active payout
   * reservations; this method is not the full payout-available calculation.
   */
  public Money rawSettledCap(UUID goalId, Money goalPoolBalance) {
    Money settled = settledGoalAmount(goalId);
    if (!settled.currencyCode().equals(goalPoolBalance.currencyCode())) {
      throw new IllegalArgumentException("settled and goal pool currencies differ");
    }
    return Money.of(
        Math.min(settled.minorUnits(), goalPoolBalance.minorUnits()), settled.currencyCode());
  }

  private void publishAfterCompletion(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }

  private Totals itemTotals(UUID settlementId) {
    return jdbc.queryForObject(
        """
        select coalesce(sum(case when direction = 'CREDIT' then gross_minor_units
                                 else -gross_minor_units end), 0) as gross,
               coalesce(sum(case when direction = 'CREDIT' then fee_minor_units
                                 else -fee_minor_units end), 0) as fees,
               coalesce(sum(case when direction = 'CREDIT' then net_minor_units
                                 else -net_minor_units end), 0) as net
        from settlement_items where settlement_id = ?
        """,
        (resultSet, rowNumber) ->
            new Totals(
                resultSet.getLong("gross"), resultSet.getLong("fees"), resultSet.getLong("net")),
        settlementId);
  }

  private record Totals(long gross, long fees, long net) {}

  private Settlement mapSettlement(ResultSet resultSet, int rowNumber) throws SQLException {
    String currency = resultSet.getString("currency_code");
    return new Settlement(
        resultSet.getObject("id", UUID.class),
        SettlementProvider.valueOf(resultSet.getString("provider")),
        resultSet.getString("provider_settlement_reference"),
        resultSet.getObject("settlement_date", java.time.LocalDate.class),
        Money.of(resultSet.getLong("gross_amount_minor_units"), currency),
        Money.of(resultSet.getLong("fees_minor_units"), currency),
        Money.of(resultSet.getLong("net_amount_minor_units"), currency),
        SettlementDirection.valueOf(resultSet.getString("direction")),
        SettlementStatus.valueOf(resultSet.getString("status")),
        instant(resultSet, "received_at"),
        instant(resultSet, "created_at"),
        instant(resultSet, "updated_at"));
  }

  private SettlementItem mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
    String currency = resultSet.getString("currency_code");
    return new SettlementItem(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("settlement_id", UUID.class),
        resultSet.getObject("contribution_id", UUID.class),
        SettlementItemType.valueOf(resultSet.getString("item_type")),
        SettlementDirection.valueOf(resultSet.getString("direction")),
        Money.of(resultSet.getLong("gross_minor_units"), currency),
        Money.of(resultSet.getLong("fee_minor_units"), currency),
        Money.of(resultSet.getLong("net_minor_units"), currency),
        Money.of(resultSet.getLong("goal_amount_minor_units"), currency),
        instant(resultSet, "created_at"),
        resultSet.getString("provider_item_reference"));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
