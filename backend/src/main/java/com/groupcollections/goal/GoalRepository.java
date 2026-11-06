package com.groupcollections.goal;

import com.groupcollections.ledger.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GoalRepository {

  private final JdbcTemplate jdbc;

  public GoalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Goal> findBySlug(String slug) {
    List<Goal> rows =
        jdbc.query(
            """
            select id, target_type, fixed_amount_pesewas, minimum_amount_pesewas, status, deadline
            from goals
            where slug = ?
            """,
            (resultSet, rowNumber) ->
                new Goal(
                    resultSet.getObject("id", UUID.class),
                    TargetType.valueOf(resultSet.getString("target_type")),
                    money(resultSet, "fixed_amount_pesewas"),
                    money(resultSet, "minimum_amount_pesewas"),
                    GoalStatus.valueOf(resultSet.getString("status")),
                    instant(resultSet, "deadline")),
            slug);
    return rows.stream().findFirst();
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
