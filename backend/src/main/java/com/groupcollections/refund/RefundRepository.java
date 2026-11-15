package com.groupcollections.refund;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {

  private static final String COLUMNS =
      """
      select id, contribution_id, goal_id, source_account, amount_minor_units,
             platform_fee_minor_units, processing_fee_minor_units, total_minor_units,
             currency_code, state, idempotency_key, provider_transaction_reference,
             provider_reference, operator_id, reason, failure_code, provider_requested_at,
             succeeded_at, failed_at, created_at, updated_at
      from refunds
      """;

  private static final RowMapper<Refund> MAPPER =
      (resultSet, rowNumber) ->
          new Refund(
              resultSet.getObject("id", UUID.class),
              resultSet.getObject("contribution_id", UUID.class),
              resultSet.getObject("goal_id", UUID.class),
              resultSet.getString("source_account"),
              resultSet.getLong("amount_minor_units"),
              resultSet.getLong("platform_fee_minor_units"),
              resultSet.getLong("processing_fee_minor_units"),
              resultSet.getLong("total_minor_units"),
              resultSet.getString("currency_code"),
              RefundState.valueOf(resultSet.getString("state")),
              resultSet.getString("idempotency_key"),
              resultSet.getString("provider_transaction_reference"),
              resultSet.getString("provider_reference"),
              resultSet.getObject("operator_id", UUID.class),
              resultSet.getString("reason"),
              resultSet.getString("failure_code"),
              instant(resultSet, "provider_requested_at"),
              instant(resultSet, "succeeded_at"),
              instant(resultSet, "failed_at"),
              instant(resultSet, "created_at"),
              instant(resultSet, "updated_at"));

  private final JdbcTemplate jdbc;

  public RefundRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public UUID insert(
      UUID id,
      UUID contributionId,
      UUID goalId,
      String sourceAccount,
      long amountMinorUnits,
      long platformFeeMinorUnits,
      long processingFeeMinorUnits,
      long totalMinorUnits,
      String currencyCode,
      String idempotencyKey,
      String providerTransactionReference,
      UUID operatorId,
      String reason,
      Instant now) {
    Timestamp timestamp = Timestamp.from(now);
    jdbc.update(
        """
        insert into refunds
            (id, contribution_id, goal_id, source_account, amount_minor_units,
             platform_fee_minor_units, processing_fee_minor_units, total_minor_units,
             currency_code, state, idempotency_key, provider_transaction_reference, operator_id,
             reason, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
        """,
        id,
        contributionId,
        goalId,
        sourceAccount,
        amountMinorUnits,
        platformFeeMinorUnits,
        processingFeeMinorUnits,
        totalMinorUnits,
        currencyCode,
        idempotencyKey,
        providerTransactionReference,
        operatorId,
        reason,
        timestamp,
        timestamp);
    return id;
  }

  public Optional<Refund> findById(UUID id) {
    return jdbc.query(COLUMNS + "where id = ?", MAPPER, id).stream().findFirst();
  }

  public Optional<Refund> findByIdForUpdate(UUID id) {
    return jdbc.query(COLUMNS + "where id = ? for update", MAPPER, id).stream().findFirst();
  }

  public Optional<Refund> findByContributionAndIdempotencyKey(
      UUID contributionId, String idempotencyKey) {
    return jdbc
        .query(
            COLUMNS + "where contribution_id = ? and idempotency_key = ?",
            MAPPER,
            contributionId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public Optional<Refund> findActiveForContribution(UUID contributionId) {
    return jdbc
        .query(
            COLUMNS
                + "where contribution_id = ? and state in ('PENDING', 'PROVIDER_PENDING', 'SUCCEEDED')",
            MAPPER,
            contributionId)
        .stream()
        .findFirst();
  }

  public List<Refund> findRecent(RefundState state, int limit) {
    if (state == null) {
      return jdbc.query(COLUMNS + "order by created_at desc limit ?", MAPPER, limit);
    }
    return jdbc.query(
        COLUMNS + "where state = ? order by created_at desc limit ?", MAPPER, state.name(), limit);
  }

  public int markProviderPending(
      UUID id, String providerReference, Instant nextVerificationAt, Instant now) {
    return jdbc.update(
        """
        update refunds
        set state = 'PROVIDER_PENDING',
            provider_reference = coalesce(?, provider_reference),
            provider_requested_at = coalesce(provider_requested_at, ?),
            next_verification_at = coalesce(next_verification_at, ?),
            updated_at = ?
        where id = ? and state in ('PENDING', 'PROVIDER_PENDING')
        """,
        providerReference,
        Timestamp.from(now),
        nextVerificationAt == null ? null : Timestamp.from(nextVerificationAt),
        Timestamp.from(now),
        id);
  }

  public int markSucceeded(UUID id, String providerReference, Instant now) {
    return jdbc.update(
        """
        update refunds
        set state = 'SUCCEEDED',
            provider_reference = coalesce(?, provider_reference),
            succeeded_at = ?,
            next_verification_at = null,
            updated_at = ?
        where id = ? and state in ('PENDING', 'PROVIDER_PENDING')
        """,
        providerReference,
        Timestamp.from(now),
        Timestamp.from(now),
        id);
  }

  public int markFailed(UUID id, String failureCode, Instant now) {
    return jdbc.update(
        """
        update refunds
        set state = 'FAILED', failure_code = ?, failed_at = ?,
            next_verification_at = null, updated_at = ?
        where id = ? and state in ('PENDING', 'PROVIDER_PENDING')
        """,
        failureCode,
        Timestamp.from(now),
        Timestamp.from(now),
        id);
  }

  /**
   * Refunds whose next automatic check is due; the claim below resolves races between instances.
   */
  public List<RefundVerificationCandidate> findDueForVerification(Instant now, int limit) {
    return jdbc.query(
        """
        select id, verification_attempts
        from refunds
        where state = 'PROVIDER_PENDING'
          and verification_exhausted_at is null
          and next_verification_at is not null
          and next_verification_at <= ?
        order by next_verification_at
        limit ?
        """,
        (resultSet, rowNumber) ->
            new RefundVerificationCandidate(
                resultSet.getObject("id", UUID.class), resultSet.getInt("verification_attempts")),
        Timestamp.from(now),
        limit);
  }

  public int claimVerification(
      UUID id, int expectedAttempts, Instant nextVerificationAt, Instant now) {
    return jdbc.update(
        """
        update refunds
        set verification_attempts = verification_attempts + 1,
            next_verification_at = ?,
            updated_at = ?
        where id = ?
          and state = 'PROVIDER_PENDING'
          and verification_exhausted_at is null
          and verification_attempts = ?
          and next_verification_at <= ?
        """,
        Timestamp.from(nextVerificationAt),
        Timestamp.from(now),
        id,
        expectedAttempts,
        Timestamp.from(now));
  }

  public int markVerificationExhausted(UUID id, Instant now) {
    return jdbc.update(
        """
        update refunds
        set verification_exhausted_at = ?, next_verification_at = null, updated_at = ?
        where id = ? and state = 'PROVIDER_PENDING' and verification_exhausted_at is null
        """,
        Timestamp.from(now),
        Timestamp.from(now),
        id);
  }

  public record RefundVerificationCandidate(UUID id, int attempts) {}

  public void appendEvent(
      UUID refundId, String event, String fromState, String toState, String detail, Instant now) {
    jdbc.update(
        """
        insert into refund_events (refund_id, event, from_state, to_state, detail, created_at)
        values (?, ?, ?, ?, ?, ?)
        """,
        refundId,
        event,
        fromState,
        toState,
        detail,
        Timestamp.from(now));
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
