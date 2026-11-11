package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.security.PhoneCipher;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakePayoutTransferProvider;
import com.groupcollections.support.FakeSmsSender;
import com.groupcollections.support.RecordingAlertPublisher;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

@Import(PayoutExecutionIT.ExecutionTestConfiguration.class)
@TestPropertySource(
    properties = {"collections.payout.enabled=true", "collections.payout.execution.enabled=true"})
class PayoutExecutionIT extends ApiIntegrationTest {

  @TestConfiguration
  static class ExecutionTestConfiguration {
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
  @Autowired PhoneCipher phones;

  private static final String PAYER = "+233240000777";

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
  @Criterion({"AC-PAYOUT-010", "INV-PAYOUT-005"})
  void successPostsOneLedgerSetAndDoesNotPostAgain() {
    Session session = session("0240000601", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payoutId = ready(session, goalId, 1_000);

    assertThat(execution.executeNext()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select count(distinct posting_set_id) from ledger_entries where payout_id = ?",
                Integer.class,
                payoutId))
        .isEqualTo(1);
    var lines =
        jdbc.queryForList(
            "select account, direction, amount_minor_units from ledger_entries where payout_id = ?",
            payoutId);
    assertThat(lines)
        .anyMatch(
            line ->
                line.get("account").equals("GOAL_POOL")
                    && line.get("direction").equals("DEBIT")
                    && ((Number) line.get("amount_minor_units")).longValue() == 1_000L);
    assertThat(lines)
        .anyMatch(
            line ->
                line.get("account").equals("PARTNER_HELD")
                    && line.get("direction").equals("CREDIT")
                    && ((Number) line.get("amount_minor_units")).longValue() == 900L);
    assertThat(lines)
        .anyMatch(
            line ->
                line.get("account").equals("PAYOUT_FEE")
                    && line.get("direction").equals("CREDIT")
                    && ((Number) line.get("amount_minor_units")).longValue() == 100L);
    assertThat(FakePayoutTransferProvider.calls()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select provider_reference from payouts where id = ?", String.class, payoutId))
        .isNotBlank();
    assertThat(FakeSmsSender.sent()).anyMatch(sent -> sent.phone().equals(PAYER));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_notifications where payout_id = ?",
                Integer.class,
                payoutId))
        .isEqualTo(1);
    assertThat(execution.executeNext()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ?", Integer.class, payoutId))
        .isEqualTo(3);
  }

  @Test
  @Criterion("AC-PAYOUT-009")
  void retriedCompletedPayoutReturnsOriginalAndPostsOnce() {
    Session session = session("0240000607", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    String key = "payout-" + UUID.randomUUID();
    ResponseEntity<Map<String, Object>> first = request(session, goalId, 1_000, key);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID payoutId = UUID.fromString((String) first.getBody().get("id"));
    makeReady(payoutId);
    assertThat(execution.executeNext()).isEqualTo(1);

    ResponseEntity<Map<String, Object>> retry = request(session, goalId, 1_000, key);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getBody().get("id")).isEqualTo(payoutId.toString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payouts where goal_id = ?", Integer.class, goalId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ?", Integer.class, payoutId))
        .isEqualTo(3);
  }

  private void makeReady(UUID payoutId) {
    jdbc.update(
        "update payouts set hold_expires_at = now() - interval '1 minute' where id = ?", payoutId);
    holds.releaseExpired();
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("READY");
  }

  @Test
  @Criterion("AC-PAYOUT-003")
  void zeroFeeOmitsTheFeeLedgerLine() {
    Session session = session("0240000602", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    FakePayoutFeeProvider.setFee(0);
    UUID payoutId = ready(session, goalId, 1_000);

    assertThat(execution.executeNext()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ?", Integer.class, payoutId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ? and account = 'PAYOUT_FEE'",
                Integer.class,
                payoutId))
        .isZero();
  }

  @Test
  @Criterion("AC-PAYOUT-015")
  void feeChangeFailsBeforeTheProviderCall() {
    Session session = session("0240000603", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payoutId = ready(session, goalId, 1_000);
    FakePayoutFeeProvider.setFee(200);

    assertThat(execution.executeNext()).isZero();
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("FAILED");
    assertThat(FakePayoutTransferProvider.calls()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ?", Integer.class, payoutId))
        .isZero();
  }

  @Test
  @Criterion("AC-PAYOUT-010")
  void providerFailureReleasesTheReservationWithoutLedger() {
    Session session = session("0240000604", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payoutId = ready(session, goalId, 1_000);
    FakePayoutTransferProvider.status(PayoutTransferProvider.TransferResult.Status.FAILED);

    assertThat(execution.executeNext()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("FAILED");
    assertThat(payouts.reservedMinorUnits(goalId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where payout_id = ?", Integer.class, payoutId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select failure_code from payouts where id = ?", String.class, payoutId))
        .isEqualTo("PROVIDER_FAILED");
  }

  @Test
  @Criterion("AC-PAYOUT-010")
  void unknownProviderResultStaysExecutingAndIsNotRetried() {
    Session session = session("0240000605", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payoutId = ready(session, goalId, 1_000);
    FakePayoutTransferProvider.status(PayoutTransferProvider.TransferResult.Status.UNKNOWN);

    assertThat(execution.executeNext()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("EXECUTING");
    assertThat(execution.executeNext()).isZero();
    assertThat(FakePayoutTransferProvider.calls()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_events where payout_id = ? and event = 'PROVIDER_UNKNOWN'",
                Integer.class,
                payoutId))
        .isEqualTo(1);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(
            alert ->
                alert.type() == com.groupcollections.alerts.Alert.AlertType.PAYOUT_EXECUTION_STUCK);
  }

  @Test
  @Criterion("AC-PAYOUT-010")
  void providerExceptionBecomesUnknownWithEventAndAlert() {
    Session session = session("0240000608", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payoutId = ready(session, goalId, 1_000);
    FakePayoutTransferProvider.throwNext();

    assertThat(execution.executeNext()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("EXECUTING");
    assertThat(FakePayoutTransferProvider.calls()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_events where payout_id = ? and event = 'PROVIDER_UNKNOWN'",
                Integer.class,
                payoutId))
        .isEqualTo(1);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(
            alert ->
                alert.type() == com.groupcollections.alerts.Alert.AlertType.PAYOUT_EXECUTION_STUCK);
  }

  @Test
  @Criterion("AC-PAYOUT-007")
  void frozenGoalIsNotExecuted() {
    Session session = session("0240000606", "Ama Mensah");
    UUID goalId = createGoal(session);
    fundGoal(session, goalId, 5_000);
    UUID payoutId = ready(session, goalId, 1_000);
    jdbc.update("update goals set status = 'FROZEN' where id = ?", goalId);

    assertThat(execution.executeNext()).isZero();
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("READY");
    assertThat(FakePayoutTransferProvider.calls()).isZero();
  }

  private UUID ready(Session session, UUID goalId, long amount) {
    ResponseEntity<Map<String, Object>> response = request(session, goalId, amount);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID payoutId = UUID.fromString((String) response.getBody().get("id"));
    jdbc.update(
        "update payouts set hold_expires_at = now() - interval '1 minute' where id = ?", payoutId);
    holds.releaseExpired();
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, payoutId))
        .isEqualTo("READY");
    return payoutId;
  }

  private UUID createGoal(Session session) {
    FakeAccountResolver.name("0240000699", "Kofi Mensah");
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Execution goal");
    body.put("template", "FUNERAL");
    body.put("targetType", "FIXED");
    body.put("fixedAmountPesewas", 5_000L);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", "BENEFICIARY_MOMO");
    body.put("destinationPhone", "0240000699");
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
        "execution-" + contributionId,
        phones.encrypt(PAYER),
        phones.hash(PAYER),
        "execution-" + contributionId);
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
        "execution-" + settlementId,
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
        "execution-" + UUID.randomUUID());
    jdbc.update(
        "update settlements set status = 'SETTLED', received_at = now() where id = ?",
        settlementId);
  }

  private ResponseEntity<Map<String, Object>> request(Session session, UUID goalId, long amount) {
    return request(session, goalId, amount, "payout-" + UUID.randomUUID());
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
