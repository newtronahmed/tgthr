package com.groupcollections.contribution;

import com.groupcollections.ledger.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContributionRepository {

  private static final Logger log = LoggerFactory.getLogger(ContributionRepository.class);

  private static final String STATUS_COLUMNS =
      """
      select id, state, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas,
             total_pesewas
      from contributions
      """;

  private static final RowMapper<ContributionStatus> STATUS_MAPPER =
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
      };

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
  public void apply(UUID id, ContributionEvent event) {
    ContributionState current = currentState(id);
    Optional<ContributionTransition> transition = ContributionStateMachine.apply(current, event);
    if (transition.isEmpty()) {
      log.warn("illegal contribution transition: event {} from state {}", event, current);
      return;
    }
    ContributionTransition legal = transition.get();
    jdbc.update(
        "update contributions set state = ?, updated_at = now() where id = ?",
        legal.to().name(),
        id);
    appendHistory(id, legal.from(), legal.to(), legal.event().name());
  }

  public ContributionStatus status(UUID id) {
    return jdbc.queryForObject(STATUS_COLUMNS + "where id = ?", STATUS_MAPPER, id);
  }

  public Optional<ContributionPayment> findByProviderReference(String providerReference) {
    List<ContributionPayment> rows =
        jdbc.query(
            """
            select id, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas, total_pesewas
            from contributions
            where provider_reference = ?
            """,
            (resultSet, rowNumber) ->
                new ContributionPayment(
                    resultSet.getObject("id", UUID.class),
                    Money.ofPesewas(resultSet.getLong("amount_pesewas")),
                    Money.ofPesewas(resultSet.getLong("processing_fee_pesewas")),
                    Money.ofPesewas(resultSet.getLong("platform_fee_pesewas")),
                    Money.ofPesewas(resultSet.getLong("total_pesewas"))),
            providerReference);
    return rows.stream().findFirst();
  }

  public Optional<ContributionStatus> findByIdempotencyKey(String idempotencyKey) {
    List<ContributionStatus> rows =
        jdbc.query(STATUS_COLUMNS + "where idempotency_key = ?", STATUS_MAPPER, idempotencyKey);
    return rows.stream().findFirst();
  }

  private ContributionState currentState(UUID id) {
    String state =
        jdbc.queryForObject(
            "select state from contributions where id = ? for update", String.class, id);
    return ContributionState.valueOf(state);
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
