package com.groupcollections.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OtpChallengeRepository {

  public record Challenge(
      UUID id,
      byte[] codeHash,
      int attempts,
      int maxAttempts,
      Instant createdAt,
      Instant expiresAt,
      Instant consumedAt) {

    public boolean active(Instant now) {
      return consumedAt == null && expiresAt.isAfter(now) && attempts < maxAttempts;
    }
  }

  private final JdbcTemplate jdbc;

  public OtpChallengeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID insert(
      byte[] phoneHash, byte[] codeHash, Instant expiresAt, int maxAttempts, Instant createdAt) {
    return jdbc.queryForObject(
        """
        insert into otp_challenges
            (phone_hash, code_hash, expires_at, max_attempts, created_at)
        values (?, ?, ?, ?, ?)
        returning id
        """,
        UUID.class,
        phoneHash,
        codeHash,
        Timestamp.from(expiresAt),
        maxAttempts,
        Timestamp.from(createdAt));
  }

  public Optional<Challenge> findLatest(byte[] phoneHash) {
    return jdbc
        .query(
            """
            select id, code_hash, attempts, max_attempts, created_at, expires_at, consumed_at
            from otp_challenges
            where phone_hash = ?
            order by created_at desc
            limit 1
            """,
            (resultSet, rowNumber) -> map(resultSet),
            phoneHash)
        .stream()
        .findFirst();
  }

  public Optional<Challenge> findLatestActive(byte[] phoneHash, Instant now) {
    return jdbc
        .query(
            """
            select id, code_hash, attempts, max_attempts, created_at, expires_at, consumed_at
            from otp_challenges
            where phone_hash = ?
              and consumed_at is null
              and expires_at > ?
              and attempts < max_attempts
            order by created_at desc
            limit 1
            """,
            (resultSet, rowNumber) -> map(resultSet),
            phoneHash,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public Optional<Integer> incrementAttempts(UUID id, Instant now) {
    return jdbc
        .query(
            """
            update otp_challenges
            set attempts = attempts + 1
            where id = ? and consumed_at is null and expires_at > ? and attempts < max_attempts
            returning attempts
            """,
            (resultSet, rowNumber) -> resultSet.getInt("attempts"),
            id,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public void consume(UUID id, Instant now) {
    jdbc.update(
        "update otp_challenges set consumed_at = ? where id = ? and consumed_at is null",
        Timestamp.from(now),
        id);
  }

  private static Challenge map(ResultSet resultSet) throws SQLException {
    return new Challenge(
        resultSet.getObject("id", UUID.class),
        resultSet.getBytes("code_hash"),
        resultSet.getInt("attempts"),
        resultSet.getInt("max_attempts"),
        instant(resultSet, "created_at"),
        instant(resultSet, "expires_at"),
        instant(resultSet, "consumed_at"));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
