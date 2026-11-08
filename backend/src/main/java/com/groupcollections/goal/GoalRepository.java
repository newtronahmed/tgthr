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
      select id, slug, title, template, target_type, fixed_amount_pesewas,
             target_amount_pesewas, minimum_amount_pesewas, status, deadline,
             destination_type, second_approver, second_approver_threshold_pesewas,
             hold_window_minutes, surplus_rule
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
              money(resultSet, "fixed_amount_pesewas"),
              money(resultSet, "target_amount_pesewas"),
              money(resultSet, "minimum_amount_pesewas"),
              GoalStatus.valueOf(resultSet.getString("status")),
              instant(resultSet, "deadline"),
              resultSet.getString("destination_type"),
              resultSet.getString("second_approver"),
              resultSet.getLong("second_approver_threshold_pesewas"),
              resultSet.getInt("hold_window_minutes"),
              resultSet.getString("surplus_rule"));

  public Optional<Goal> findBySlug(String slug) {
    return jdbc.query(GOAL_COLUMNS + "where slug = ?", GOAL_MAPPER, slug).stream().findFirst();
  }

  /** Shared lock so a goal cannot be closed or frozen between the check and the insert. */
  public Optional<Goal> findBySlugForShare(String slug) {
    return jdbc.query(GOAL_COLUMNS + "where slug = ? for share", GOAL_MAPPER, slug).stream()
        .findFirst();
  }

  private static Money money(ResultSet resultSet, String column) throws SQLException {
    long pesewas = resultSet.getLong(column);
    return resultSet.wasNull() ? null : Money.ofPesewas(pesewas);
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
