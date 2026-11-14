package com.groupcollections.operator;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperatorEnrollmentRepository {

  public record Enrollment(
      UUID id, UUID operatorId, Instant expiresAt, Instant passwordSetAt, Instant consumedAt) {}

  private final JdbcTemplate jdbc;

  public OperatorEnrollmentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID operatorId, byte[] tokenHash, Instant now, Instant expiresAt) {
    jdbc.update(
        """
        insert into operator_enrollment_tokens (operator_id, token_hash, created_at, expires_at)
        values (?, ?, ?, ?)
        """,
        operatorId,
        tokenHash,
        Timestamp.from(now),
        Timestamp.from(expiresAt));
  }

  public Optional<Enrollment> findByTokenHash(byte[] tokenHash) {
    return jdbc
        .query(
            """
            select id, operator_id, expires_at, password_set_at, consumed_at
            from operator_enrollment_tokens
            where token_hash = ?
            """,
            (resultSet, rowNumber) -> {
              OffsetDateTime passwordSet =
                  resultSet.getObject("password_set_at", OffsetDateTime.class);
              OffsetDateTime consumedAt = resultSet.getObject("consumed_at", OffsetDateTime.class);
              return new Enrollment(
                  resultSet.getObject("id", UUID.class),
                  resultSet.getObject("operator_id", UUID.class),
                  resultSet.getObject("expires_at", OffsetDateTime.class).toInstant(),
                  passwordSet == null ? null : passwordSet.toInstant(),
                  consumedAt == null ? null : consumedAt.toInstant());
            },
            tokenHash)
        .stream()
        .findFirst();
  }

  public int markPasswordSet(UUID id, Instant now) {
    return jdbc.update(
        """
        update operator_enrollment_tokens
        set password_set_at = ?
        where id = ? and password_set_at is null and consumed_at is null
        """,
        Timestamp.from(now),
        id);
  }

  public int consume(UUID id, Instant now) {
    return jdbc.update(
        """
        update operator_enrollment_tokens
        set consumed_at = ?
        where id = ? and consumed_at is null
        """,
        Timestamp.from(now),
        id);
  }
}
