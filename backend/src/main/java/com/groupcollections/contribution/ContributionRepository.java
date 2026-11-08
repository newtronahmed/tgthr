package com.groupcollections.contribution;

import com.groupcollections.ledger.Money;
import com.groupcollections.security.PhoneCipher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
             total_pesewas, provider_reference, confirmed_at
      from contributions
      """;

  private static final RowMapper<ContributionStatus> STATUS_MAPPER =
      (resultSet, rowNumber) -> {
        long processing = resultSet.getLong("processing_fee_pesewas");
        long platform = resultSet.getLong("platform_fee_pesewas");
        ContributionState state = ContributionState.valueOf(resultSet.getString("state"));
        OffsetDateTime confirmedAt = resultSet.getObject("confirmed_at", OffsetDateTime.class);
        ContributionStatus.Receipt receipt =
            state == ContributionState.CONFIRMED && confirmedAt != null
                ? new ContributionStatus.Receipt(
                    resultSet.getString("provider_reference"), confirmedAt.toInstant())
                : null;
        return new ContributionStatus(
            resultSet.getObject("id", UUID.class),
            state,
            resultSet.getLong("amount_pesewas"),
            processing,
            platform,
            processing + platform,
            resultSet.getLong("total_pesewas"),
            receipt);
      };

  private static final String PAYMENT_COLUMNS =
      """
      select id, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas, total_pesewas
      from contributions
      """;

  private static final RowMapper<ContributionPayment> PAYMENT_MAPPER =
      (resultSet, rowNumber) ->
          new ContributionPayment(
              resultSet.getObject("id", UUID.class),
              Money.ofPesewas(resultSet.getLong("amount_pesewas")),
              Money.ofPesewas(resultSet.getLong("processing_fee_pesewas")),
              Money.ofPesewas(resultSet.getLong("platform_fee_pesewas")),
              Money.ofPesewas(resultSet.getLong("total_pesewas")));

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ContributionRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public boolean insertInitiated(
      UUID id,
      UUID goalId,
      FeeBreakdown fees,
      CreateContributionRequest request,
      PhoneCipher phones,
      String idempotencyKey) {
    int inserted =
        jdbc.update(
            """
        insert into contributions
            (id, goal_id, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas,
             total_pesewas, state, provider_reference, payer_phone_ciphertext, payer_phone_hash,
             display_name, anonymous, idempotency_key, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, 'INITIATED', ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (idempotency_key) do nothing
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
            idempotencyKey,
            Timestamp.from(Instant.now(clock)),
            Timestamp.from(Instant.now(clock)));
    if (inserted == 0) {
      return false;
    }
    appendHistory(id, null, ContributionState.INITIATED, "CREATED");
    return true;
  }

  @Transactional
  public Optional<ContributionTransition> apply(UUID id, ContributionEvent event) {
    ContributionState current = currentState(id);
    Optional<ContributionTransition> transition = ContributionStateMachine.apply(current, event);
    if (transition.isEmpty()) {
      log.warn(
          "illegal contribution transition for {}: event {} from state {}", id, event, current);
      return Optional.empty();
    }
    ContributionTransition legal = transition.get();
    Timestamp now = Timestamp.from(Instant.now(clock));
    if (legal.to() == ContributionState.CONFIRMED) {
      jdbc.update(
          "update contributions set state = ?, updated_at = ?, confirmed_at = ? where id = ?",
          legal.to().name(),
          now,
          now,
          id);
    } else {
      jdbc.update(
          "update contributions set state = ?, updated_at = ? where id = ?",
          legal.to().name(),
          now,
          id);
    }
    appendHistory(id, legal.from(), legal.to(), legal.event().name());
    return transition;
  }

  public Optional<ContributionStatus> findStatus(UUID id) {
    return jdbc.query(STATUS_COLUMNS + "where id = ?", STATUS_MAPPER, id).stream().findFirst();
  }

  public ContributionStatus status(UUID id) {
    return findStatus(id).orElseThrow(() -> new ContributionNotFoundException(id));
  }

  public Optional<ContributionPayment> findByProviderReference(String providerReference) {
    List<ContributionPayment> rows =
        jdbc.query(
            PAYMENT_COLUMNS + "where provider_reference = ?", PAYMENT_MAPPER, providerReference);
    return rows.stream().findFirst();
  }

  public Optional<ContributionPayment> findPayment(UUID id) {
    List<ContributionPayment> rows =
        jdbc.query(PAYMENT_COLUMNS + "where id = ?", PAYMENT_MAPPER, id);
    return rows.stream().findFirst();
  }

  public Money goalRaised(UUID goalId) {
    Long sum =
        jdbc.queryForObject(
            """
            select coalesce(sum(case
                       when le.entry_type = 'CONTRIBUTION' and le.direction = 'CREDIT'
                           then le.amount_pesewas
                       when le.entry_type = 'REVERSAL' and le.direction = 'DEBIT'
                           then -le.amount_pesewas
                       else 0
                   end), 0)
            from ledger_entries le
            join contributions c on c.id = le.contribution_id
            where c.goal_id = ?
            """,
            Long.class,
            goalId);
    return Money.ofPesewas(sum == null ? 0 : sum);
  }

  public long confirmedCount(UUID goalId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from contributions where goal_id = ? and state = 'CONFIRMED'",
            Long.class,
            goalId);
    return count == null ? 0 : count;
  }

  public List<PublicGoalView.FeedItem> feed(UUID goalId, int limit) {
    return jdbc.query(
        """
        select display_name, anonymous, amount_pesewas, confirmed_at
        from contributions
        where goal_id = ? and state = 'CONFIRMED'
        order by confirmed_at desc nulls last
        limit ?
        """,
        (resultSet, rowNumber) -> {
          boolean anonymous = resultSet.getBoolean("anonymous");
          OffsetDateTime confirmedAt = resultSet.getObject("confirmed_at", OffsetDateTime.class);
          return new PublicGoalView.FeedItem(
              anonymous ? null : resultSet.getString("display_name"),
              anonymous,
              resultSet.getLong("amount_pesewas"),
              confirmedAt == null ? null : confirmedAt.toInstant());
        },
        goalId,
        limit);
  }

  /**
   * Selects PENDING contributions past the cutoff. SKIP LOCKED avoids blocking on rows another
   * transaction is changing; duplicate polling is safe because resolution is idempotent.
   */
  public List<ContributionPending> findPendingDue(Instant cutoff, int limit) {
    return jdbc.query(
        """
        select id, provider_reference, updated_at
        from contributions
        where state = 'PENDING' and updated_at <= ?
        order by updated_at
        limit ?
        for update skip locked
        """,
        (resultSet, rowNumber) -> pending(resultSet),
        Timestamp.from(cutoff),
        limit);
  }

  public Optional<ContributionStatus> findByIdempotencyKey(String idempotencyKey) {
    List<ContributionStatus> rows =
        jdbc.query(STATUS_COLUMNS + "where idempotency_key = ?", STATUS_MAPPER, idempotencyKey);
    return rows.stream().findFirst();
  }

  /** Terminal rows worth a bounded re-check; a later success must never be lost. */
  public List<ContributionPending> findTerminalForRecheck(
      Instant cutoff, Instant lookbackStart, int limit) {
    return jdbc.query(
        """
        select id, provider_reference, updated_at
        from contributions
        where state in ('EXPIRED', 'FAILED') and updated_at <= ? and updated_at >= ?
        order by updated_at
        limit ?
        for update skip locked
        """,
        (resultSet, rowNumber) -> pending(resultSet),
        Timestamp.from(cutoff),
        Timestamp.from(lookbackStart),
        limit);
  }

  /** INITIATED rows older than the timeout; a crash may have left them behind. */
  public List<ContributionPending> findInitiatedStale(Instant cutoff, int limit) {
    return jdbc.query(
        """
        select id, provider_reference, updated_at
        from contributions
        where state = 'INITIATED' and updated_at <= ?
        order by updated_at
        limit ?
        for update skip locked
        """,
        (resultSet, rowNumber) -> pending(resultSet),
        Timestamp.from(cutoff),
        limit);
  }

  private static ContributionPending pending(java.sql.ResultSet resultSet)
      throws java.sql.SQLException {
    return new ContributionPending(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("provider_reference"),
        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
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
        insert into contribution_events
            (contribution_id, from_state, to_state, event, occurred_at)
        values (?, ?, ?, ?, ?)
        """,
        contributionId,
        from == null ? null : from.name(),
        to.name(),
        event,
        Timestamp.from(Instant.now(clock)));
  }
}
