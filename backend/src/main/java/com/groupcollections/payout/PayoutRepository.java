package com.groupcollections.payout;

import com.groupcollections.ledger.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PayoutRepository {

  private static final String COLUMNS =
      """
      select id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             currency_code, state, idempotency_key, provider_reference, created_at
      from payouts
      """;

  private final JdbcTemplate jdbc;

  public PayoutRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Payout> findByGoalAndIdempotencyKey(UUID goalId, String key) {
    return jdbc
        .query(COLUMNS + " where goal_id = ? and idempotency_key = ?", this::map, goalId, key)
        .stream()
        .findFirst();
  }

  public List<Payout> findByGoalAndRequester(UUID goalId, UUID requesterId) {
    return jdbc.query(
        COLUMNS + " where goal_id = ? and requested_by = ? order by created_at desc, id desc",
        this::map,
        goalId,
        requesterId);
  }

  public List<PayoutPublicView> findPublicForGoal(UUID goalId) {
    return jdbc.query(
        """
        select id, amount_minor_units, fee_minor_units, net_minor_units, currency_code, state,
               hold_expires_at, completed_at
        from payouts
        where goal_id = ? and state in ('HOLD', 'READY', 'EXECUTING', 'COMPLETED')
        order by created_at, id
        """,
        (resultSet, rowNumber) ->
            new PayoutPublicView(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("amount_minor_units"),
                resultSet.getLong("fee_minor_units"),
                resultSet.getLong("net_minor_units"),
                resultSet.getString("currency_code"),
                resultSet.getString("state"),
                instant(resultSet, "hold_expires_at"),
                instant(resultSet, "completed_at")),
        goalId);
  }

  public Optional<Payout> findById(UUID id) {
    return jdbc.query(COLUMNS + " where id = ?", this::map, id).stream().findFirst();
  }

  public Optional<Payout> findByIdAndRequester(UUID id, UUID requesterId) {
    return jdbc
        .query(COLUMNS + " where id = ? and requested_by = ?", this::map, id, requesterId)
        .stream()
        .findFirst();
  }

  public UUID insert(
      UUID goalId,
      UUID requesterId,
      long amountPesewas,
      long feePesewas,
      long netPesewas,
      String state,
      String idempotencyKey,
      Instant now) {
    Timestamp timestamp = Timestamp.from(now);
    return jdbc
        .query(
            """
            insert into payouts
                (goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
                 state, idempotency_key, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (goal_id, idempotency_key) do nothing
            returning id
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            goalId,
            requesterId,
            amountPesewas,
            feePesewas,
            netPesewas,
            state,
            idempotencyKey,
            timestamp,
            timestamp)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public int markCancelled(UUID id, String expectedState, Instant now) {
    return jdbc.update(
        """
        update payouts set state = 'CANCELLED', cancelled_at = ?, updated_at = ?
        where id = ? and state = ?
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        id,
        expectedState);
  }

  public int startHold(UUID id, Instant expiresAt, Instant now) {
    return jdbc.update(
        """
        update payouts
        set state = 'HOLD', approved_at = coalesce(approved_at, ?), hold_expires_at = ?,
            updated_at = ?
        where id = ? and state = 'APPROVED'
        """,
        Timestamp.from(now),
        Timestamp.from(expiresAt),
        Timestamp.from(now),
        id);
  }

  public List<UUID> expiredHolds(Instant now) {
    return jdbc.query(
        """
        select id from payouts
        where state = 'HOLD' and hold_expires_at <= ?
        order by hold_expires_at
        for update skip locked
        """,
        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
        Timestamp.from(now));
  }

  public int markReady(UUID id, Instant now) {
    return jdbc.update(
        """
        update payouts set state = 'READY', updated_at = ?
        where id = ? and state = 'HOLD' and hold_expires_at <= ?
        """,
        Timestamp.from(now),
        id,
        Timestamp.from(now));
  }

  public long completedMinorUnits(UUID goalId) {
    Long completed =
        jdbc.queryForObject(
            "select coalesce(sum(amount_minor_units), 0) from payouts where goal_id = ? and state = 'COMPLETED'",
            Long.class,
            goalId);
    return completed == null ? 0 : completed;
  }

  public int markApproved(UUID id, Instant now) {
    return jdbc.update(
        """
        update payouts set state = 'APPROVED', approved_at = ?, updated_at = ?
        where id = ? and state = 'REQUESTED'
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        id);
  }

  public long reservedMinorUnits(UUID goalId) {
    Long reserved =
        jdbc.queryForObject(
            """
            select coalesce(sum(amount_minor_units), 0)
            from payouts
            where goal_id = ?
              and state in ('REQUESTED', 'APPROVED', 'HOLD', 'READY', 'EXECUTING')
            """,
            Long.class,
            goalId);
    return reserved == null ? 0 : reserved;
  }

  public Money goalPoolBalance(UUID goalId) {
    Long balance =
        jdbc.queryForObject(
            """
            select coalesce(sum(case when le.direction = 'CREDIT'
                                     then le.amount_minor_units
                                     else -le.amount_minor_units end), 0)
            from ledger_entries le
            left join contributions c on c.id = le.contribution_id
            left join payouts p on p.id = le.payout_id
            where le.account = 'GOAL_POOL' and (c.goal_id = ? or p.goal_id = ?)
            """,
            Long.class,
            goalId,
            goalId);
    return Money.of(balance == null ? 0 : balance, "GHS");
  }

  public void appendEvent(
      UUID payoutId, String event, String fromState, String toState, Instant now) {
    jdbc.update(
        """
        insert into payout_events (payout_id, event, from_state, to_state, occurred_at)
        values (?, ?, ?, ?, ?)
        """,
        payoutId,
        event,
        fromState,
        toState,
        Timestamp.from(now));
  }

  private Payout map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Payout(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("goal_id", UUID.class),
        resultSet.getObject("requested_by", UUID.class),
        resultSet.getLong("amount_minor_units"),
        resultSet.getLong("fee_minor_units"),
        resultSet.getLong("net_minor_units"),
        resultSet.getString("currency_code"),
        resultSet.getString("state"),
        resultSet.getString("idempotency_key"),
        resultSet.getString("provider_reference"),
        instant(resultSet, "created_at"));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
