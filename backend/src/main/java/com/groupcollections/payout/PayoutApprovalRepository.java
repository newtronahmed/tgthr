package com.groupcollections.payout;

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
public class PayoutApprovalRepository {

  private static final String COLUMNS =
      """
      select id, payout_id, token_hash, code_hash, token_expires_at, code_expires_at,
             attempts, max_attempts, consumed_at, created_at
      from payout_approvals
      """;

  private final JdbcTemplate jdbc;

  public PayoutApprovalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID insert(
      UUID payoutId,
      byte[] tokenHash,
      byte[] codeHash,
      Instant tokenExpiresAt,
      Instant codeExpiresAt,
      int maxAttempts,
      Instant now) {
    return jdbc
        .query(
            """
            insert into payout_approvals
                (payout_id, token_hash, code_hash, token_expires_at, code_expires_at,
                 max_attempts, created_at)
            values (?, ?, ?, ?, ?, ?, ?)
            returning id
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            payoutId,
            tokenHash,
            codeHash,
            Timestamp.from(tokenExpiresAt),
            Timestamp.from(codeExpiresAt),
            maxAttempts,
            Timestamp.from(now))
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("approval challenge insert failed"));
  }

  public Optional<PayoutApproval> findActiveForPayout(UUID payoutId, Instant now) {
    return jdbc
        .query(
            COLUMNS
                + " where payout_id = ? and consumed_at is null and token_expires_at > ?"
                + " order by created_at desc limit 1",
            this::map,
            payoutId,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public int consumeExpiredForPayout(UUID payoutId, Instant now) {
    return jdbc.update(
        """
        update payout_approvals set consumed_at = ?
        where payout_id = ? and consumed_at is null and token_expires_at <= ?
        """,
        Timestamp.from(now),
        payoutId,
        Timestamp.from(now));
  }

  public Optional<PayoutApproval> findByTokenHash(byte[] tokenHash) {
    return jdbc.query(COLUMNS + " where token_hash = ?", this::map, (Object) tokenHash).stream()
        .findFirst();
  }

  public Optional<Integer> incrementAttempts(UUID id, Instant now) {
    return jdbc
        .query(
            """
            update payout_approvals set attempts = attempts + 1
            where id = ? and consumed_at is null
            returning attempts
            """,
            (resultSet, rowNumber) -> resultSet.getInt("attempts"),
            id)
        .stream()
        .findFirst();
  }

  public int consume(UUID id, Instant now) {
    return jdbc.update(
        "update payout_approvals set consumed_at = ? where id = ? and consumed_at is null",
        Timestamp.from(now),
        id);
  }

  public List<PayoutApproval> findExpiredTokens(Instant now) {
    return jdbc.query(
        COLUMNS + " where consumed_at is null and token_expires_at <= ?",
        this::map,
        Timestamp.from(now));
  }

  private PayoutApproval map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new PayoutApproval(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("payout_id", UUID.class),
        resultSet.getBytes("token_hash"),
        resultSet.getBytes("code_hash"),
        instant(resultSet, "token_expires_at"),
        instant(resultSet, "code_expires_at"),
        resultSet.getInt("attempts"),
        resultSet.getInt("max_attempts"),
        instant(resultSet, "consumed_at"),
        instant(resultSet, "created_at"));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
