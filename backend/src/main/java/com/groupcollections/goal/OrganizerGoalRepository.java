package com.groupcollections.goal;

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
public class OrganizerGoalRepository {

  private static final String COLUMNS =
      """
      select g.id, g.slug, g.title, g.template, g.target_type, g.fixed_amount_pesewas,
             g.target_amount_pesewas, g.minimum_amount_pesewas, g.status, g.deadline,
             g.destination_type, g.destination_account_name, g.second_approver,
             g.second_approver_threshold_pesewas, g.hold_window_minutes, g.surplus_rule,
             g.created_at,
             coalesce((select sum(case
                         when le.entry_type = 'CONTRIBUTION' and le.direction = 'CREDIT'
                             then le.amount_pesewas
                         when le.entry_type = 'REVERSAL' and le.direction = 'DEBIT'
                             then -le.amount_pesewas
                         else 0
                     end)
                 from ledger_entries le
                 join contributions c on c.id = le.contribution_id
                 where c.goal_id = g.id), 0) as raised_pesewas,
             (select count(*) from contributions c
              where c.goal_id = g.id and c.state = 'CONFIRMED') as contributor_count
      from goals g
      """;

  private final JdbcTemplate jdbc;

  public OrganizerGoalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> insert(
      String slug,
      CreateGoalRequest request,
      long minimumAmountPesewas,
      long secondApproverThresholdPesewas,
      int holdWindowMinutes,
      SurplusRule surplusRule,
      UUID creatorId,
      byte[] destinationCiphertext,
      byte[] destinationHash,
      String destinationAccountName,
      String idempotencyKey,
      Instant now) {
    Timestamp timestamp = Timestamp.from(now);
    return jdbc
        .query(
            """
            insert into goals
                (slug, title, template, target_type, fixed_amount_pesewas,
                 target_amount_pesewas, minimum_amount_pesewas, deadline, status,
                 destination_type, second_approver, hold_window_minutes, surplus_rule,
                 created_by_user_id, destination_phone_ciphertext, destination_phone_hash,
                 destination_network, destination_account_name, destination_locked_at,
                 second_approver_threshold_pesewas, create_idempotency_key, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict do nothing
            returning id
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            slug,
            request.title(),
            request.template().name(),
            request.targetType().name(),
            request.fixedAmountPesewas(),
            request.targetAmountPesewas(),
            minimumAmountPesewas,
            Timestamp.from(request.deadline()),
            request.destinationType().name(),
            request.secondApprover().name(),
            holdWindowMinutes,
            surplusRule.name(),
            creatorId,
            destinationCiphertext,
            destinationHash,
            request.network().name(),
            destinationAccountName,
            timestamp,
            secondApproverThresholdPesewas,
            idempotencyKey,
            timestamp,
            timestamp)
        .stream()
        .findFirst();
  }

  public Optional<OrganizerGoal> findByIdAndCreator(UUID id, UUID creatorId) {
    return jdbc
        .query(COLUMNS + " where g.id = ? and g.created_by_user_id = ?", this::map, id, creatorId)
        .stream()
        .findFirst();
  }

  public Optional<OrganizerGoal> findByCreatorAndIdempotencyKey(
      UUID creatorId, String idempotencyKey) {
    return jdbc
        .query(
            COLUMNS + " where g.created_by_user_id = ? and g.create_idempotency_key = ?",
            this::map,
            creatorId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public List<OrganizerGoal> listByCreator(UUID creatorId) {
    return jdbc.query(
        COLUMNS + " where g.created_by_user_id = ? order by g.created_at desc",
        this::map,
        creatorId);
  }

  public int close(UUID id, UUID creatorId, Instant now) {
    return jdbc.update(
        """
        update goals set status = 'CLOSED', updated_at = ?
        where id = ? and created_by_user_id = ? and status = 'OPEN'
        """,
        Timestamp.from(now),
        id,
        creatorId);
  }

  public void appendEvent(
      UUID goalId, String event, String fromStatus, String toStatus, UUID actorId, Instant now) {
    jdbc.update(
        """
        insert into goal_events (goal_id, event, from_status, to_status, actor_user_id, occurred_at)
        values (?, ?, ?, ?, ?, ?)
        """,
        goalId,
        event,
        fromStatus,
        toStatus,
        actorId,
        Timestamp.from(now));
  }

  private OrganizerGoal map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new OrganizerGoal(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("slug"),
        resultSet.getString("title"),
        GoalTemplate.valueOf(resultSet.getString("template")),
        TargetType.valueOf(resultSet.getString("target_type")),
        resultSet.getObject("fixed_amount_pesewas", Long.class),
        resultSet.getObject("target_amount_pesewas", Long.class),
        resultSet.getObject("minimum_amount_pesewas", Long.class),
        GoalStatus.valueOf(resultSet.getString("status")),
        instant(resultSet, "deadline"),
        DestinationType.valueOf(resultSet.getString("destination_type")),
        resultSet.getString("destination_account_name"),
        SecondApprover.valueOf(resultSet.getString("second_approver")),
        resultSet.getLong("second_approver_threshold_pesewas"),
        resultSet.getInt("hold_window_minutes"),
        SurplusRule.valueOf(resultSet.getString("surplus_rule")),
        resultSet.getLong("raised_pesewas"),
        resultSet.getLong("contributor_count"),
        instant(resultSet, "created_at"));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
