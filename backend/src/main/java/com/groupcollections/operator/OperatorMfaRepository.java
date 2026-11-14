package com.groupcollections.operator;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperatorMfaRepository {

  public record Challenge(
      UUID id,
      UUID operatorId,
      Instant expiresAt,
      int attempts,
      int maxAttempts,
      Instant consumedAt) {}

  private final JdbcTemplate jdbc;

  public OperatorMfaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      UUID operatorId, byte[] tokenHash, Instant now, Instant expiresAt, int maxAttempts) {
    jdbc.update(
        """
        insert into operator_mfa_challenges
            (operator_id, token_hash, created_at, expires_at, max_attempts)
        values (?, ?, ?, ?, ?)
        """,
        operatorId,
        tokenHash,
        Timestamp.from(now),
        Timestamp.from(expiresAt),
        maxAttempts);
  }

  public Optional<Challenge> findByTokenHash(byte[] tokenHash) {
    return jdbc
        .query(
            """
            select id, operator_id, expires_at, attempts, max_attempts, consumed_at
            from operator_mfa_challenges
            where token_hash = ?
            """,
            (resultSet, rowNumber) -> {
              OffsetDateTime consumedAt = resultSet.getObject("consumed_at", OffsetDateTime.class);
              return new Challenge(
                  resultSet.getObject("id", UUID.class),
                  resultSet.getObject("operator_id", UUID.class),
                  resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                  resultSet.getInt("attempts"),
                  resultSet.getInt("max_attempts"),
                  consumedAt == null ? null : consumedAt.toInstant());
            },
            tokenHash)
        .stream()
        .findFirst();
  }

  public int incrementAttempts(UUID challengeId) {
    return jdbc.update(
        "update operator_mfa_challenges set attempts = attempts + 1 where id = ?", challengeId);
  }

  public int consume(UUID challengeId, Instant now) {
    return jdbc.update(
        """
        update operator_mfa_challenges
        set consumed_at = ?
        where id = ? and consumed_at is null
        """,
        Timestamp.from(now),
        challengeId);
  }

  public int consumeOpenForOperator(UUID operatorId, Instant now) {
    return jdbc.update(
        """
        update operator_mfa_challenges
        set consumed_at = ?
        where operator_id = ? and consumed_at is null
        """,
        Timestamp.from(now),
        operatorId);
  }
}
