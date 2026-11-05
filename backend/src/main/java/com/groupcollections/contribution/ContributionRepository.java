package com.groupcollections.contribution;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContributionRepository {

  private final JdbcTemplate jdbc;

  public ContributionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void insertInitiated(
      UUID id,
      UUID goalId,
      FeeBreakdown fees,
      CreateContributionRequest request,
      PayerPhoneCipher phones,
      String idempotencyKey) {
    jdbc.update(
        """
        insert into contributions
            (id, goal_id, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas,
             total_pesewas, state, provider_reference, payer_phone_ciphertext, payer_phone_hash,
             display_name, anonymous, idempotency_key)
        values (?, ?, ?, ?, ?, ?, 'INITIATED', ?, ?, ?, ?, ?, ?)
        """,
        id,
        goalId,
        fees.amount().pesewas(),
        fees.processingFee().pesewas(),
        fees.platformFee().pesewas(),
        fees.total().pesewas(),
        id.toString(),
        phones.encrypt(request.momoNumber()),
        phones.hash(request.momoNumber()),
        request.displayName(),
        request.anonymousOrFalse(),
        idempotencyKey);
    appendHistory(id, null, ContributionState.INITIATED, "CREATED");
  }

  @Transactional
  public void markPending(UUID id) {
    transition(id, ContributionState.PENDING, "PROMPT_SENT");
  }

  @Transactional
  public void markFailed(UUID id) {
    transition(id, ContributionState.FAILED, "PROMPT_REJECTED");
  }

  public ContributionStatus status(UUID id) {
    return jdbc.queryForObject(
        """
        select id, state, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas,
               total_pesewas
        from contributions
        where id = ?
        """,
        (resultSet, rowNumber) -> {
          long processing = resultSet.getLong("processing_fee_pesewas");
          long platform = resultSet.getLong("platform_fee_pesewas");
          return new ContributionStatus(
              resultSet.getObject("id", UUID.class),
              ContributionState.valueOf(resultSet.getString("state")),
              resultSet.getLong("amount_pesewas"),
              processing,
              platform,
              processing + platform,
              resultSet.getLong("total_pesewas"));
        },
        id);
  }

  private void transition(UUID id, ContributionState to, String event) {
    jdbc.update(
        "update contributions set state = ?, updated_at = now() where id = ?", to.name(), id);
    appendHistory(id, ContributionState.INITIATED, to, event);
  }

  private void appendHistory(
      UUID contributionId, ContributionState from, ContributionState to, String event) {
    jdbc.update(
        """
        insert into contribution_events (contribution_id, from_state, to_state, event)
        values (?, ?, ?, ?)
        """,
        contributionId,
        from == null ? null : from.name(),
        to.name(),
        event);
  }
}
