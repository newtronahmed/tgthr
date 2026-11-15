package com.groupcollections.refund;

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
public class ReconciliationRepository {

  private static final String COLUMNS =
      """
      select id, contribution_id, resolution, status, proposer_id, approver_id, reason,
             idempotency_key, approval_idempotency_key, posting_set_id, created_at, approved_at
      from reconciliation_resolutions
      """;

  private final JdbcTemplate jdbc;

  public ReconciliationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ReconciliationResolution> findByContribution(UUID contributionId) {
    return jdbc.query(COLUMNS + "where contribution_id = ?", this::map, contributionId).stream()
        .findFirst();
  }

  public Optional<ReconciliationResolution> findByContributionForUpdate(UUID contributionId) {
    return jdbc
        .query(COLUMNS + "where contribution_id = ? for update", this::map, contributionId)
        .stream()
        .findFirst();
  }

  public UUID insert(
      UUID id,
      UUID contributionId,
      String resolution,
      String status,
      UUID proposerId,
      UUID approverId,
      String reason,
      String idempotencyKey,
      String approvalIdempotencyKey,
      UUID postingSetId,
      Instant now,
      Instant approvedAt) {
    jdbc.update(
        """
        insert into reconciliation_resolutions
            (id, contribution_id, resolution, status, proposer_id, approver_id, reason,
             idempotency_key, approval_idempotency_key, posting_set_id, created_at, approved_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        contributionId,
        resolution,
        status,
        proposerId,
        approverId,
        reason,
        idempotencyKey,
        approvalIdempotencyKey,
        postingSetId,
        Timestamp.from(now),
        approvedAt == null ? null : Timestamp.from(approvedAt));
    return id;
  }

  public int markApproved(
      UUID id,
      UUID approverId,
      UUID postingSetId,
      String approvalIdempotencyKey,
      Instant approvedAt) {
    return jdbc.update(
        """
        update reconciliation_resolutions
        set status = 'APPLIED', approver_id = ?, posting_set_id = ?,
            approval_idempotency_key = ?, approved_at = ?
        where id = ? and status = 'PROPOSED'
        """,
        approverId,
        postingSetId,
        approvalIdempotencyKey,
        Timestamp.from(approvedAt),
        id);
  }

  private ReconciliationResolution map(ResultSet resultSet, int rowNumber) throws SQLException {
    OffsetDateTime approvedAt = resultSet.getObject("approved_at", OffsetDateTime.class);
    return new ReconciliationResolution(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("contribution_id", UUID.class),
        resultSet.getString("resolution"),
        resultSet.getString("status"),
        resultSet.getObject("proposer_id", UUID.class),
        resultSet.getObject("approver_id", UUID.class),
        resultSet.getString("reason"),
        resultSet.getString("idempotency_key"),
        resultSet.getString("approval_idempotency_key"),
        resultSet.getObject("posting_set_id", UUID.class),
        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
        approvedAt == null ? null : approvedAt.toInstant());
  }
}
