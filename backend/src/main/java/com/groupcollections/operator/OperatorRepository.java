package com.groupcollections.operator;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperatorRepository {

  private static final String COLUMNS =
      """
      select id, username, password_hash, totp_secret_ciphertext, status, failed_attempts,
             locked_until, last_totp_step
      from operators
      """;

  private final JdbcTemplate jdbc;

  public OperatorRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Operator> findByUsername(String username) {
    if (username == null || username.isBlank()) {
      return Optional.empty();
    }
    return jdbc.query(COLUMNS + " where lower(username) = lower(?)", this::map, username).stream()
        .findFirst();
  }

  public Optional<Operator> findById(UUID id) {
    return jdbc.query(COLUMNS + " where id = ?", this::map, id).stream().findFirst();
  }

  public UUID insert(
      UUID id,
      String username,
      String passwordHash,
      byte[] totpSecretCiphertext,
      OperatorStatus status,
      Instant now) {
    Timestamp timestamp = Timestamp.from(now);
    jdbc.update(
        """
        insert into operators
            (id, username, password_hash, totp_secret_ciphertext, status, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        username,
        passwordHash,
        totpSecretCiphertext,
        status.name(),
        timestamp,
        timestamp);
    return id;
  }

  public Set<OperatorRole> roles(UUID operatorId) {
    List<String> roles =
        jdbc.queryForList(
            "select role from operator_roles where operator_id = ?", String.class, operatorId);
    Set<OperatorRole> result = EnumSet.noneOf(OperatorRole.class);
    for (String role : roles) {
      result.add(OperatorRole.valueOf(role));
    }
    return result;
  }

  public void grantRole(UUID operatorId, OperatorRole role, UUID grantedBy, Instant now) {
    jdbc.update(
        """
        insert into operator_roles (operator_id, role, granted_by, granted_at)
        values (?, ?, ?, ?)
        on conflict (operator_id, role) do nothing
        """,
        operatorId,
        role.name(),
        grantedBy,
        Timestamp.from(now));
  }

  public void recordFailedAttempt(
      UUID operatorId, Instant now, int maxAttempts, Instant lockedUntil) {
    jdbc.update(
        """
        update operators
        set failed_attempts = failed_attempts + 1,
            locked_until = case
                when failed_attempts + 1 >= ? then ?
                else locked_until
            end,
            updated_at = ?
        where id = ?
        """,
        maxAttempts,
        Timestamp.from(lockedUntil),
        Timestamp.from(now),
        operatorId);
  }

  public void resetFailures(UUID operatorId, Instant now) {
    jdbc.update(
        """
        update operators
        set failed_attempts = 0, locked_until = null, updated_at = ?
        where id = ?
        """,
        Timestamp.from(now),
        operatorId);
  }

  /**
   * Returns 1 only when the step advances, so a replayed or raced code cannot be consumed twice.
   */
  public int updateLastTotpStep(UUID operatorId, long step, Instant now) {
    return jdbc.update(
        """
        update operators
        set last_totp_step = ?, updated_at = ?
        where id = ? and last_totp_step < ?
        """,
        step,
        Timestamp.from(now),
        operatorId,
        step);
  }

  public void setStatus(UUID operatorId, OperatorStatus status, Instant now) {
    jdbc.update(
        "update operators set status = ?, updated_at = ? where id = ?",
        status.name(),
        Timestamp.from(now),
        operatorId);
  }

  public void audit(UUID operatorId, String event, String reason, Instant now) {
    jdbc.update(
        """
        insert into operator_auth_events (operator_id, event, reason, created_at)
        values (?, ?, ?, ?)
        """,
        operatorId,
        event,
        reason,
        Timestamp.from(now));
  }

  public long count() {
    Long count = jdbc.queryForObject("select count(*) from operators", Long.class);
    return count == null ? 0 : count;
  }

  private Operator map(ResultSet resultSet, int rowNumber) throws SQLException {
    OffsetDateTime lockedUntil = resultSet.getObject("locked_until", OffsetDateTime.class);
    return new Operator(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("username"),
        resultSet.getString("password_hash"),
        resultSet.getBytes("totp_secret_ciphertext"),
        OperatorStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("failed_attempts"),
        lockedUntil == null ? null : lockedUntil.toInstant(),
        resultSet.getLong("last_totp_step"));
  }
}
