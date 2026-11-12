package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.ledger.LedgerAccount;
import com.groupcollections.ledger.LedgerDirection;
import com.groupcollections.ledger.LedgerEntry;
import com.groupcollections.ledger.LedgerEntryType;
import com.groupcollections.ledger.LedgerRepository;
import com.groupcollections.ledger.Money;
import com.groupcollections.ledger.PostingSet;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakeSmsSender;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@Import(PayoutRequestIT.PayoutRequestTestConfiguration.class)
@TestPropertySource(properties = "collections.payout.enabled=true")
class PayoutRequestIT extends ApiIntegrationTest {

  @Autowired LedgerRepository ledger;
  @Autowired PayoutHoldService holds;

  @TestConfiguration
  static class PayoutRequestTestConfiguration {
    @Bean
    @Primary
    SmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }

    @Bean
    @Primary
    AccountResolver fakeAccountResolver() {
      return new FakeAccountResolver();
    }

    @Bean
    @Primary
    PayoutFeeProvider fakePayoutFeeProvider() {
      return new FakePayoutFeeProvider();
    }
  }

  @BeforeEach
  void resetFakes() {
    FakeSmsSender.reset();
    FakeAccountResolver.reset();
    FakePayoutFeeProvider.reset();
  }

  @Test
  @Criterion("AC-PAYOUT-020")
  void payoutRequestRequiresConfiguredApprover() {
    Session session = session("0240000301", "Ama Mensah");
    UUID goalId = createGoal(session, "ABOVE_THRESHOLD", null, null);
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isZero();
    double rejectedBefore = counter("payout.rejected", "reason", "approver_required");

    ResponseEntity<Map<String, Object>> response = request(session, goalId, 1_000, uniqueKey());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("code")).isEqualTo("APPROVER_REQUIRED");
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isZero();
    assertThat(counter("payout.rejected", "reason", "approver_required"))
        .isGreaterThan(rejectedBefore);
  }

  @Test
  @Criterion("AC-PAYOUT-020")
  void settingsPatchAndPayoutRequestRaceWithoutAnUnapprovedPayout() throws Exception {
    Session session = session("0240000390", "Ama Mensah");
    UUID goalId = createGoal(session, "ABOVE_THRESHOLD", null, null);
    fundGoal(session, goalId, 5_000, true);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    ResponseEntity<Map<String, Object>> payout;
    ResponseEntity<Map<String, Object>> settings;
    try {
      Future<ResponseEntity<Map<String, Object>>> payoutFuture =
          pool.submit(() -> requestAfter(start, session, goalId, 1_000));
      Future<ResponseEntity<Map<String, Object>>> settingsFuture =
          pool.submit(
              () ->
                  settingsAfter(
                      start,
                      session,
                      goalId,
                      Map.of(
                          "secondApproverName", "Esi Mensah",
                          "secondApproverPhone", "0240000391")));
      start.countDown();
      payout = payoutFuture.get(10, TimeUnit.SECONDS);
      settings = settingsFuture.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    // The goal lock serialises the two writers. The settings PATCH always wins or follows a
    // rejected payout; a payout can only be created after the approver is committed.
    assertThat(settings.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(payout.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);
    if (payout.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
      assertThat(payout.getBody()).isNotNull();
      assertThat(payout.getBody().get("code")).isEqualTo("APPROVER_REQUIRED");
    }
    assertThat(
            count(
                """
                select count(*) from payouts p join goals g on g.id = p.goal_id
                where p.goal_id = ? and g.second_approver <> 'OFF'
                  and g.second_approver_name is null
                """,
                goalId))
        .isZero();
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId))
        .isEqualTo(payout.getStatusCode() == HttpStatus.CREATED ? 1 : 0);

    ResponseEntity<Map<String, Object>> after =
        updateSettings(session, goalId, Map.of("holdWindowMinutes", 45));
    assertThat(after.getStatusCode())
        .isEqualTo(
            payout.getStatusCode() == HttpStatus.CREATED ? HttpStatus.CONFLICT : HttpStatus.OK);
  }

  @Test
  @Criterion("AC-PAYOUT-001")
  void quoteReturnsExactFeeNetAndAvailableAndRejectsAtFee() {
    Session session = session("0240000305", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);

    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    ResponseEntity<Map<String, Object>> quote =
        rest.exchange(
            "/organizer/goals/{goalId}/payout-quote?amountPesewas=1000",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(quote.getBody()).isNotNull();
    assertThat(((Number) quote.getBody().get("amountPesewas")).longValue()).isEqualTo(1_000L);
    assertThat(((Number) quote.getBody().get("feePesewas")).longValue()).isEqualTo(100L);
    assertThat(((Number) quote.getBody().get("netPesewas")).longValue()).isEqualTo(900L);
    assertThat(((Number) quote.getBody().get("availablePesewas")).longValue()).isEqualTo(5_000L);

    ResponseEntity<Map<String, Object>> atFee =
        rest.exchange(
            "/organizer/goals/{goalId}/payout-quote?amountPesewas=100",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(atFee.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(atFee.getBody()).isNotNull();
    assertThat(atFee.getBody().get("code")).isEqualTo("AMOUNT_TOO_LOW");

    Session other = session("0240000306", "Kofi Mensah");
    HttpHeaders otherHeaders = new HttpHeaders();
    otherHeaders.add(HttpHeaders.COOKIE, "tgthr_session=" + other.token());
    ResponseEntity<Map<String, Object>> foreign =
        rest.exchange(
            "/organizer/goals/{goalId}/payout-quote?amountPesewas=1000",
            HttpMethod.GET,
            new HttpEntity<>(otherHeaders),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @Criterion("AC-PAYOUT-001")
  void partialPayoutUsesSettledAvailableBalance() {
    Session session = session("0240000310", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);
    double requestedBefore = counter("payout.requests");

    ResponseEntity<Map<String, Object>> response = request(session, goalId, 3_000, uniqueKey());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(((Number) response.getBody().get("amountPesewas")).longValue()).isEqualTo(3_000L);
    assertThat(((Number) response.getBody().get("feePesewas")).longValue()).isEqualTo(100L);
    assertThat(((Number) response.getBody().get("netPesewas")).longValue()).isEqualTo(2_900L);
    assertThat(response.getBody().get("state")).isEqualTo("HOLD");
    assertThat(counter("payout.requests")).isGreaterThan(requestedBefore);
    Map<String, Object> row =
        jdbc.queryForMap("select id, state from payouts where goal_id = ?", goalId);
    assertThat(row.get("state")).isEqualTo("HOLD");
    assertThat(
            count(
                "select count(*) from payout_events where payout_id = ? and event in ('REQUESTED','APPROVED','HOLD')",
                row.get("id")))
        .isEqualTo(3);
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);

    ResponseEntity<Map<String, Object>> tooMuch = request(session, goalId, 3_000, uniqueKey());
    assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tooMuch.getBody()).isNotNull();
    assertThat(tooMuch.getBody().get("code")).isEqualTo("INSUFFICIENT_SETTLED_BALANCE");
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);
  }

  @Test
  @Criterion("AC-PAYOUT-002")
  void unsettledFundsAreNotPayoutEligible() {
    Session session = session("0240000320", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, false);

    ResponseEntity<Map<String, Object>> response = request(session, goalId, 1_000, uniqueKey());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_SETTLED_BALANCE");
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isZero();
  }

  @Test
  @Criterion({"AC-PAYOUT-002", "INV-PAYOUT-001", "INV-SETTLE-001", "INV-LEDGER-003"})
  void settledCashCapsReservations() {
    Session session = session("0240000325", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoalWithSettled(session, goalId, 5_000, 4_000);

    ResponseEntity<Map<String, Object>> first = request(session, goalId, 3_000, uniqueKey());
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ResponseEntity<Map<String, Object>> over = request(session, goalId, 1_500, uniqueKey());
    assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(over.getBody()).isNotNull();
    assertThat(over.getBody().get("code")).isEqualTo("INSUFFICIENT_SETTLED_BALANCE");
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);
  }

  @Test
  @Criterion("AC-PAYOUT-002")
  void completedPayoutsReduceTheSettledCapAsWellAsThePool() {
    Session session = session("0240000326", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoalWithSettled(session, goalId, 5_000, 4_000);
    completePayout(session, goalId, 1_000);

    ResponseEntity<Map<String, Object>> over = request(session, goalId, 3_500, uniqueKey());
    assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(over.getBody()).isNotNull();
    assertThat(over.getBody().get("code")).isEqualTo("INSUFFICIENT_SETTLED_BALANCE");
    ResponseEntity<Map<String, Object>> allowed = request(session, goalId, 2_500, uniqueKey());
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  @Criterion({"AC-PAYOUT-023", "INV-PAYOUT-003"})
  void positiveHoldExpiresToReadyAndFreezeBlocksRequests() {
    Session session = session("0240000327", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);
    ResponseEntity<Map<String, Object>> requested = request(session, goalId, 1_000, uniqueKey());
    assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(requested.getBody()).isNotNull();
    assertThat(requested.getBody().get("state")).isEqualTo("HOLD");

    jdbc.update(
        "update payouts set hold_expires_at = now() - interval '1 minute' where goal_id = ?",
        goalId);
    assertThat(holds.releaseExpired()).isGreaterThanOrEqualTo(1);
    Map<String, Object> ready =
        jdbc.queryForMap("select id, state from payouts where goal_id = ?", goalId);
    assertThat(ready.get("state")).isEqualTo("READY");
    assertThat(
            count(
                "select count(*) from payout_events where payout_id = ? and event = 'READY'",
                ready.get("id")))
        .isEqualTo(1);

    UUID frozenGoal = createGoal(session, "OFF", null, null);
    fundGoal(session, frozenGoal, 5_000, true);
    jdbc.update("update goals set status = 'FROZEN' where id = ?", frozenGoal);
    ResponseEntity<Map<String, Object>> frozen = request(session, frozenGoal, 1_000, uniqueKey());
    assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(frozen.getBody()).isNotNull();
    assertThat(frozen.getBody().get("code")).isEqualTo("GOAL_FROZEN");
    assertThat(count("select count(*) from payouts where goal_id = ?", frozenGoal)).isZero();
  }

  @Test
  @Criterion("AC-PAYOUT-016")
  void zeroHoldIsRefusedAtRequestTime() {
    Session session = session("0240000328", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);
    jdbc.update("update goals set hold_window_minutes = 0 where id = ?", goalId);

    ResponseEntity<Map<String, Object>> response = request(session, goalId, 1_000, uniqueKey());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("code")).isEqualTo("HOLD_REQUIRED");
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isZero();
  }

  @Test
  @Criterion("AC-PAYOUT-021")
  void feeAndBeneficiaryNetAreExactAndGoalBorne() {
    Session session = session("0240000330", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);
    FakePayoutFeeProvider.setFee(100);

    ResponseEntity<Map<String, Object>> response = request(session, goalId, 1_000, uniqueKey());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) response.getBody().get("amountPesewas")).longValue()).isEqualTo(1_000L);
    assertThat(((Number) response.getBody().get("feePesewas")).longValue()).isEqualTo(100L);
    assertThat(((Number) response.getBody().get("netPesewas")).longValue()).isEqualTo(900L);
    Map<String, Object> stored =
        jdbc.queryForMap(
            "select amount_minor_units, fee_minor_units, net_minor_units from payouts where goal_id = ?",
            goalId);
    assertThat(((Number) stored.get("amount_minor_units")).longValue()).isEqualTo(1_000L);
    assertThat(((Number) stored.get("fee_minor_units")).longValue()).isEqualTo(100L);
    assertThat(((Number) stored.get("net_minor_units")).longValue()).isEqualTo(900L);

    ResponseEntity<Map<String, Object>> tooSmall = request(session, goalId, 100, uniqueKey());
    assertThat(tooSmall.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tooSmall.getBody()).isNotNull();
    assertThat(tooSmall.getBody().get("code")).isEqualTo("AMOUNT_TOO_LOW");
  }

  @Test
  @Criterion({"AC-PAYOUT-022", "INV-PAYOUT-007"})
  void inFlightPayoutsReserveAvailableFunds() throws Exception {
    Session session = session("0240000340", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<ResponseEntity<Map<String, Object>>> first =
          pool.submit(() -> requestAfter(start, session, goalId, 3_000));
      Future<ResponseEntity<Map<String, Object>>> second =
          pool.submit(() -> requestAfter(start, session, goalId, 3_000));
      start.countDown();
      List<HttpStatus> statuses =
          List.of(
              HttpStatus.valueOf(first.get(10, TimeUnit.SECONDS).getStatusCode().value()),
              HttpStatus.valueOf(second.get(10, TimeUnit.SECONDS).getStatusCode().value()));
      assertThat(statuses)
          .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);
    ResponseEntity<Map<String, Object>> remaining = request(session, goalId, 2_000, uniqueKey());
    assertThat(remaining.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ResponseEntity<Map<String, Object>> none = request(session, goalId, 1, uniqueKey());
    assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  @Criterion("AC-PAYOUT-018")
  void retryReturnsOriginalAndDifferentBodyConflicts() {
    Session session = session("0240000350", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);
    String key = uniqueKey();

    ResponseEntity<Map<String, Object>> first = request(session, goalId, 1_000, key);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) first.getBody().get("id");
    ResponseEntity<Map<String, Object>> retry = request(session, goalId, 1_000, key);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getBody().get("id")).isEqualTo(id);
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);

    ResponseEntity<Map<String, Object>> conflict = request(session, goalId, 1_100, key);
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody()).isNotNull();
    assertThat(conflict.getBody().get("code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);
  }

  @Test
  void completedPayoutsReduceThePoolButNotTheReservationBucket() {
    Session session = session("0240000360", "Ama Mensah");
    UUID goalId = createGoal(session, "OFF", null, null);
    fundGoal(session, goalId, 5_000, true);
    UUID completed = UUID.randomUUID();
    jdbc.update(
        """
        insert into payouts
            (id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             state, idempotency_key, completed_at)
        values (?, ?, ?, 1000, 0, 1000, 'COMPLETED', ?, now())
        """,
        completed,
        goalId,
        session.userId(),
        "completed-" + UUID.randomUUID());
    UUID posting = UUID.randomUUID();
    jdbc.update(
        """
        insert into ledger_entries
            (posting_set_id, payout_id, account, entry_type, direction, amount_minor_units)
        values (?, ?, 'GOAL_POOL', 'PAYOUT', 'DEBIT', 1000),
               (?, ?, 'PARTNER_HELD', 'PAYOUT', 'CREDIT', 1000)
        """,
        posting,
        completed,
        posting,
        completed);

    ResponseEntity<Map<String, Object>> tooMuch = request(session, goalId, 4_500, uniqueKey());
    assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tooMuch.getBody()).isNotNull();
    assertThat(tooMuch.getBody().get("code")).isEqualTo("INSUFFICIENT_SETTLED_BALANCE");
    ResponseEntity<Map<String, Object>> allowed = request(session, goalId, 3_000, uniqueKey());
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private void completePayout(Session session, UUID goalId, long amount) {
    UUID completed = UUID.randomUUID();
    jdbc.update(
        """
        insert into payouts
            (id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             state, idempotency_key, completed_at)
        values (?, ?, ?, ?, 0, ?, 'COMPLETED', ?, now())
        """,
        completed,
        goalId,
        session.userId(),
        amount,
        amount,
        "completed-" + UUID.randomUUID());
    ledger.postPayout(
        completed,
        new PostingSet(
            UUID.randomUUID(),
            List.of(
                new LedgerEntry(
                    LedgerAccount.GOAL_POOL,
                    LedgerEntryType.PAYOUT,
                    LedgerDirection.DEBIT,
                    Money.of(amount, "GHS")),
                new LedgerEntry(
                    LedgerAccount.PARTNER_HELD,
                    LedgerEntryType.PAYOUT,
                    LedgerDirection.CREDIT,
                    Money.of(amount, "GHS")))));
  }

  private UUID createGoal(
      Session session, String secondApprover, String approverName, String approverPhone) {
    FakeAccountResolver.name("0240000399", "Kofi Mensah");
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Ama's funeral");
    body.put("template", "FUNERAL");
    body.put("targetType", "FIXED");
    body.put("fixedAmountPesewas", 5_000L);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", "BENEFICIARY_MOMO");
    body.put("destinationPhone", "0240000399");
    body.put("network", "MTN");
    body.put("secondApprover", secondApprover);
    body.put("holdWindowMinutes", 60);
    body.put("surplusRule", "PAY_BENEFICIARY");
    if (approverName != null) {
      body.put("secondApproverName", approverName);
      body.put("secondApproverPhone", approverPhone);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", uniqueKey());
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    ResponseEntity<Map<String, Object>> created =
        rest.exchange(
            "/organizer/goals",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            new ParameterizedTypeReference<>() {});
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isNotNull();
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private void fundGoal(Session session, UUID goalId, long amount, boolean settled) {
    fundGoalWithSettled(session, goalId, amount, amount, settled);
  }

  private void fundGoalWithSettled(
      Session session, UUID goalId, long poolAmount, long settledAmount) {
    fundGoalWithSettled(session, goalId, poolAmount, settledAmount, true);
  }

  private void fundGoalWithSettled(
      Session session, UUID goalId, long poolAmount, long settledAmount, boolean settled) {
    long amount = poolAmount;
    UUID contributionId = UUID.randomUUID();
    String reference = "payout-fund-" + UUID.randomUUID();
    jdbc.update(
        """
        insert into contributions
            (id, goal_id, amount_minor_units, processing_fee_minor_units, platform_fee_minor_units,
             total_minor_units, state, provider_reference, payer_phone_ciphertext,
             payer_phone_hash, idempotency_key, confirmed_at)
        values (?, ?, ?, 0, 0, ?, 'CONFIRMED', ?, ?, ?, ?, now())
        """,
        contributionId,
        goalId,
        amount,
        amount,
        reference,
        "cipher".getBytes(StandardCharsets.UTF_8),
        new byte[32],
        "fund-" + UUID.randomUUID());
    UUID posting = UUID.randomUUID();
    jdbc.update(
        """
        insert into ledger_entries
            (posting_set_id, contribution_id, account, entry_type, direction, amount_minor_units)
        values (?, ?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', ?),
               (?, ?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', ?)
        """,
        posting,
        contributionId,
        amount,
        posting,
        contributionId,
        amount);
    if (settled) {
      UUID settlementId = UUID.randomUUID();
      jdbc.update(
          """
          insert into settlements
              (id, provider, provider_settlement_reference, settlement_date,
               gross_amount_minor_units, currency_code, fees_minor_units, net_amount_minor_units,
               direction, status)
          values (?, 'PAYSTACK', ?, current_date, ?, 'GHS', 0, ?, 'CREDIT', 'PENDING')
          """,
          settlementId,
          "settle-" + UUID.randomUUID(),
          settledAmount,
          settledAmount);
      jdbc.update(
          """
          insert into settlement_items
              (id, settlement_id, contribution_id, item_type, direction, gross_minor_units,
               currency_code, fee_minor_units, net_minor_units, goal_amount_minor_units,
               provider_item_reference)
          values (?, ?, ?, 'CONTRIBUTION', 'CREDIT', ?, 'GHS', 0, ?, ?, ?)
          """,
          UUID.randomUUID(),
          settlementId,
          contributionId,
          settledAmount,
          settledAmount,
          settledAmount,
          "item-" + UUID.randomUUID());
      jdbc.update(
          "update settlements set status = 'SETTLED', received_at = now() where id = ?",
          settlementId);
    }
  }

  private ResponseEntity<Map<String, Object>> request(
      Session session, UUID goalId, long amount, String key) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/goals/{goalId}/payouts",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("amountPesewas", amount), headers),
        new ParameterizedTypeReference<>() {},
        goalId);
  }

  private ResponseEntity<Map<String, Object>> requestAfter(
      CountDownLatch start, Session session, UUID goalId, long amount) throws Exception {
    start.await();
    return request(session, goalId, amount, uniqueKey());
  }

  private ResponseEntity<Map<String, Object>> settingsAfter(
      CountDownLatch start, Session session, UUID goalId, Map<String, Object> body)
      throws Exception {
    start.await();
    return updateSettings(session, goalId, body);
  }

  private ResponseEntity<Map<String, Object>> updateSettings(
      Session session, UUID goalId, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/goals/{id}/payout-settings",
        HttpMethod.PATCH,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {},
        goalId);
  }

  private Session session(String phone, String legalName) {
    requestOtp(phone);
    ResponseEntity<Map<String, Object>> verified =
        verify(phone, FakeSmsSender.latestCode(), legalName);
    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    String setCookie = verified.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).startsWith("tgthr_session=");
    String token = setCookie.substring("tgthr_session=".length(), setCookie.indexOf(';'));
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + token);
    ResponseEntity<Map<String, Object>> me =
        rest.exchange(
            "/organizer/me",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {});
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    return new Session(token, UUID.fromString((String) me.getBody().get("id")));
  }

  private void requestOtp(String phone) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/auth/otp/request",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("phone", phone), headers),
            new ParameterizedTypeReference<>() {});
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  private ResponseEntity<Map<String, Object>> verify(String phone, String code, String legalName) {
    Map<String, Object> body = new HashMap<>();
    body.put("phone", phone);
    body.put("code", code);
    body.put("displayName", "Ama");
    if (legalName != null) {
      body.put("legalName", legalName);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/auth/otp/verify",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {});
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private static String uniqueKey() {
    return "payout-" + UUID.randomUUID();
  }

  private record Session(String token, UUID userId) {}
}
