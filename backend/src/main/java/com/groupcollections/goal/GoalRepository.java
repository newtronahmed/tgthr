package com.groupcollections.goal;

import com.groupcollections.ledger.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GoalRepository {

  private final JdbcTemplate jdbc;

  public GoalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String GOAL_COLUMNS =
      """
      select id, slug, title, template, target_type, fixed_amount_minor_units,
             target_amount_minor_units, minimum_amount_minor_units, status, deadline,
             destination_type, second_approver_name, second_approver,
             second_approver_threshold_minor_units,
             hold_window_minutes, surplus_rule, currency_code
      from goals
      """;

  private static final RowMapper<Goal> GOAL_MAPPER =
      (resultSet, rowNumber) ->
          new Goal(
              resultSet.getObject("id", UUID.class),
              resultSet.getString("slug"),
              resultSet.getString("title"),
              resultSet.getString("template"),
              TargetType.valueOf(resultSet.getString("target_type")),
              money(resultSet, "fixed_amount_minor_units"),
              money(resultSet, "target_amount_minor_units"),
              money(resultSet, "minimum_amount_minor_units"),
              GoalStatus.valueOf(resultSet.getString("status")),
              instant(resultSet, "deadline"),
              resultSet.getString("destination_type"),
              resultSet.getString("second_approver_name"),
              resultSet.getString("second_approver"),
              resultSet.getLong("second_approver_threshold_minor_units"),
              resultSet.getInt("hold_window_minutes"),
              resultSet.getString("surplus_rule"));

  public Optional<Goal> findBySlug(String slug) {
    return jdbc.query(GOAL_COLUMNS + "where slug = ?", GOAL_MAPPER, slug).stream().findFirst();
  }

  /** Shared lock so a goal cannot be closed or frozen while a contribution is confirmed. */
  public Optional<Goal> findByIdForShare(UUID id) {
    return jdbc.query(GOAL_COLUMNS + "where id = ? for share", GOAL_MAPPER, id).stream()
        .findFirst();
  }

  /** Shared lock so a goal cannot be closed or frozen between the check and the insert. */
  public Optional<Goal> findBySlugForShare(String slug) {
    return jdbc.query(GOAL_COLUMNS + "where slug = ? for share", GOAL_MAPPER, slug).stream()
        .findFirst();
  }

  private static Money money(ResultSet resultSet, String column) throws SQLException {
    long minorUnits = resultSet.getLong(column);
    return resultSet.wasNull() ? null : Money.of(minorUnits, resultSet.getString("currency_code"));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
