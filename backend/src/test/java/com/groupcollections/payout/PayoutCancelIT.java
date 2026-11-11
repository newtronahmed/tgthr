package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakePayoutTransferProvider;
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

@Import(PayoutCancelIT.CancelTestConfiguration.class)
@TestPropertySource(
    properties = {"collections.payout.enabled=true", "collections.payout.execution.enabled=true"})
class PayoutCancelIT extends ApiIntegrationTest {

  @TestConfiguration
  static class CancelTestConfiguration {
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

    @Bean
    @Primary
    PayoutTransferProvider fakePayoutTransferProvider() {
      return new FakePayoutTransferProvider();
    }
  }

  @Autowired PayoutExecutionService execution;
  @Autowired PayoutHoldService holds;
  @Autowired PayoutRepository payouts;

  @BeforeEach
  void resetFakes() {
    jdbc.update(
        "update payouts set state = 'CANCELLED' where state in ('READY','HOLD','REQUESTED','APPROVED','EXECUTING')");
    FakeSmsSender.reset();
    FakeAccountResolver.reset();
    FakePayoutFeeProvider.reset();
    FakePayoutTransferProvider.reset();
  }

  @Test
  @Criterion({"AC-PAYOUT-008", "AC-PAYOUT-013", "INV-PAYOUT-003"})
  void cancelReleasesTheReservationAndRejectsExecutingOrForeignPayouts() {
    Session session = session("0240000701", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID held = request(session, goalId, 1_000);
    assertThat(cancel(session, held).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(jdbc.queryForObject("select state from payouts where id = ?", String.class, held))
        .isEqualTo("CANCELLED");
    assertThat(payouts.reservedMinorUnits(goalId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ?", Integer.class, held))
        .isZero();

    Session other = session("0240000702", "Kofi Mensah");
    assertThat(cancel(other, held).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    UUID executing = request(session, goalId, 1_000);
    jdbc.update("update payouts set state = 'EXECUTING' where id = ?", executing);
    ResponseEntity<Map<String, Object>> rejected = cancel(session, executing);
    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(rejected.getBody()).isNotNull();
    assertThat(rejected.getBody().get("code")).isEqualTo("PAYOUT_NOT_CANCELLABLE");
  }

  @Test
  @Criterion("AC-PAYOUT-011")
  void payoutsAreScopedToTheOwner() {
    Session owner = session("0240000704", "Ama Mensah");
    UUID goalId = createGoal(owner);
    fundGoal(owner, goalId, 5_000);
    UUID payout = request(owner, goalId, 1_000);
    Session other = session("0240000705", "Kofi Mensah");

    ResponseEntity<Map<String, Object>> otherList =
        rest.exchange(
            "/organizer/goals/{goalId}/payouts",
            HttpMethod.GET,
            new HttpEntity<>(cookie(other)),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(otherList.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<Map<String, Object>> otherQuote =
        rest.exchange(
            "/organizer/goals/{goalId}/payout-quote?amountPesewas=1000",
            HttpMethod.GET,
            new HttpEntity<>(cookie(other)),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(otherQuote.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(cancel(other, payout).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<List<Map<String, Object>>> ownerList =
        rest.exchange(
            "/organizer/goals/{goalId}/payouts",
            HttpMethod.GET,
            new HttpEntity<>(cookie(owner)),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(ownerList.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ownerList.getBody()).hasSize(1);
    assertThat(ownerList.getBody().get(0).toString()).doesNotContain("0240000704");
  }

  private HttpHeaders cookie(Session session) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return headers;
  }

  @Test
  @Criterion("AC-PAYOUT-019")
  void cancelAndExecuteRaceHasOneWinner() throws Exception {
    Session session = session("0240000703", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payout = request(session, goalId, 1_000);
    makeReady(payout);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<ResponseEntity<Map<String, Object>>> cancelFuture =
          pool.submit(() -> cancelAfter(start, session, payout));
      Future<Integer> executeFuture =
          pool.submit(
              () -> {
                start.await();
                return execution.executeNext();
              });
      start.countDown();
      ResponseEntity<Map<String, Object>> cancelled = cancelFuture.get(10, TimeUnit.SECONDS);
      int executed = executeFuture.get(10, TimeUnit.SECONDS);
      String state =
          jdbc.queryForObject("select state from payouts where id = ?", String.class, payout);
      if (cancelled.getStatusCode() == HttpStatus.OK) {
        assertThat(executed).isZero();
        assertThat(state).isEqualTo("CANCELLED");
        assertThat(FakePayoutTransferProvider.calls()).isZero();
      } else {
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(executed).isEqualTo(1);
        assertThat(state).isEqualTo("COMPLETED");
        assertThat(FakePayoutTransferProvider.calls()).isEqualTo(1);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private ResponseEntity<Map<String, Object>> cancelAfter(
      CountDownLatch start, Session session, UUID payout) throws Exception {
    start.await();
    return cancel(session, payout);
  }

  private void makeReady(UUID payout) {
    jdbc.update(
        "update payouts set hold_expires_at = now() - interval '1 minute' where id = ?", payout);
    holds.releaseExpired();
    assertThat(jdbc.queryForObject("select state from payouts where id = ?", String.class, payout))
        .isEqualTo("READY");
  }

  private UUID request(Session session, UUID goalId, long amount) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "payout-" + UUID.randomUUID());
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/organizer/goals/{goalId}/payouts",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("amountPesewas", amount), headers),
            new ParameterizedTypeReference<>() {},
            goalId);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) response.getBody().get("id"));
  }

  private ResponseEntity<Map<String, Object>> cancel(Session session, UUID payout) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/payouts/{id}/cancel",
        HttpMethod.POST,
        new HttpEntity<>(headers),
        new ParameterizedTypeReference<>() {},
        payout);
  }

  private UUID createGoal(Session session) {
    FakeAccountResolver.name("0240000799", "Kofi Mensah");
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Cancel goal");
    body.put("template", "FUNERAL");
    body.put("targetType", "FIXED");
    body.put("fixedAmountPesewas", 5_000L);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", "BENEFICIARY_MOMO");
    body.put("destinationPhone", "0240000799");
    body.put("network", "MTN");
    body.put("secondApprover", "OFF");
    body.put("holdWindowMinutes", 60);
    body.put("surplusRule", "PAY_BENEFICIARY");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "goal-" + UUID.randomUUID());
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/organizer/goals",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            new ParameterizedTypeReference<>() {});
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) response.getBody().get("id"));
  }

  private void fundGoal(Session session, UUID goalId, long amount) {
    UUID contributionId = UUID.randomUUID();
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
        "cancel-" + contributionId,
        "cipher".getBytes(StandardCharsets.UTF_8),
        new byte[32],
        "cancel-" + contributionId);
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
        "cancel-" + settlementId,
        amount,
        amount);
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
        amount,
        amount,
        amount,
        "cancel-" + UUID.randomUUID());
    jdbc.update(
        "update settlements set status = 'SETTLED', received_at = now() where id = ?",
        settlementId);
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

  private record Session(String token, UUID userId) {}
}
