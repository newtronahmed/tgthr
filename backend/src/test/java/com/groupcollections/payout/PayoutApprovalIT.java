package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakeSmsSender;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

@Import(PayoutApprovalIT.ApprovalTestConfiguration.class)
@TestPropertySource(
    properties = {
      "collections.payout.enabled=true",
      "collections.payout.approval-resend-seconds=1"
    })
class PayoutApprovalIT extends ApiIntegrationTest {

  private static final Pattern TOKEN = Pattern.compile("/approve/([A-Za-z0-9_-]+)");
  private static final String APPROVER_PHONE = "+233240000402";

  @TestConfiguration
  static class ApprovalTestConfiguration {
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

  @Autowired PayoutApprovalService approvals;

  @BeforeEach
  void resetFakes() {
    FakeSmsSender.reset();
    FakeAccountResolver.reset();
    FakePayoutFeeProvider.reset();
  }

  @Test
  void failedApprovalSmsCanBeRetriedWithTheSameIdempotencyKey() {
    Session session = session("0240000405", "Ama Mensah");
    UUID goalId = createGoal(session, "ALWAYS", "Esi Mensah", APPROVER_PHONE);
    fundGoal(session, goalId, 5_000);
    String key = "payout-sms-" + UUID.randomUUID();

    FakeSmsSender.failNext(true);
    ResponseEntity<Map<String, Object>> failed = request(session, goalId, 1_000, key);
    assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(count("select count(*) from payouts where goal_id = ?", goalId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select state from payouts where goal_id = ?", String.class, goalId))
        .isEqualTo("REQUESTED");
    assertThat(
            count(
                "select count(*) from payout_approvals where consumed_at is null and payout_id = (select id from payouts where goal_id = ?)",
                goalId))
        .isZero();

    FakeSmsSender.failNext(false);
    ResponseEntity<Map<String, Object>> retried = request(session, goalId, 1_000, key);
    assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retried.getBody()).isNotNull();
    assertThat(retried.getBody().get("state")).isEqualTo("REQUESTED");
    assertThat(
            count(
                "select count(*) from payout_approvals where consumed_at is null and payout_id = (select id from payouts where goal_id = ?)",
                goalId))
        .isEqualTo(1);
    assertThat(FakeSmsSender.sent().getLast().phone()).isEqualTo(APPROVER_PHONE);
  }

  @Test
  @Criterion("AC-PAYOUT-004")
  void approverApprovesAndDeclineCancels() {
    Session session = session("0240000401", "Ama Mensah");
    UUID goalId = createGoal(session, "ALWAYS", "Esi Mensah", APPROVER_PHONE);
    fundGoal(session, goalId, 5_000);

    ResponseEntity<Map<String, Object>> requested = request(session, goalId, 1_000);
    assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(requested.getBody()).isNotNull();
    assertThat(requested.getBody().get("state")).isEqualTo("REQUESTED");
    assertThat(FakeSmsSender.sent().getLast().phone()).isEqualTo(APPROVER_PHONE);

    String token = smsToken();
    String code = FakeSmsSender.latestCode();
    ResponseEntity<Map<String, Object>> page = approvalGet(token);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page.getBody()).isNotNull();
    assertThat(page.getBody().get("approverName")).isEqualTo("Esi Mensah");
    assertThat((String) page.getBody().toString()).doesNotContain(APPROVER_PHONE);
    assertThat((String) page.getBody().toString()).doesNotContain("0240000401");

    ResponseEntity<Map<String, Object>> wrong = approvalVerify(token, "000000");
    assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    ResponseEntity<Map<String, Object>> approved = approvalVerify(token, code);
    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(approved.getBody()).isNotNull();
    assertThat(approved.getBody().get("state")).isEqualTo("HOLD");
    assertThat(
            jdbc.queryForObject(
                "select state from payouts where goal_id = ?", String.class, goalId))
        .isEqualTo("HOLD");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_approvals where payout_id = (select id from payouts where goal_id = ?) and consumed_at is not null",
                Integer.class,
                goalId))
        .isEqualTo(1);
    assertThat(approvalVerify(token, code).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<Map<String, Object>> second = request(session, goalId, 1_000);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String secondToken = smsToken();
    ResponseEntity<Map<String, Object>> declined = decline(secondToken);
    assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(declined.getBody()).isNotNull();
    assertThat(declined.getBody().get("state")).isEqualTo("CANCELLED");
    UUID secondId = UUID.fromString((String) second.getBody().get("id"));
    assertThat(
            jdbc.queryForObject("select state from payouts where id = ?", String.class, secondId))
        .isEqualTo("CANCELLED");
    assertThat(count("select count(*) from ledger_entries where payout_id = ?", secondId)).isZero();
  }

  @Test
  @Criterion("INV-PAYOUT-004")
  void approvalValuesAreHashedAtRest() {
    Session session = session("0240000404", "Ama Mensah");
    UUID goalId = createGoal(session, "ALWAYS", "Esi Mensah", APPROVER_PHONE);
    fundGoal(session, goalId, 5_000);
    request(session, goalId, 1_000);
    String token = smsToken();
    String code = FakeSmsSender.latestCode();
    Map<String, Object> stored =
        jdbc.queryForMap(
            """
            select token_hash, code_hash from payout_approvals
            where payout_id = (select id from payouts where goal_id = ?)
            """,
            goalId);
    assertThat((byte[]) stored.get("token_hash")).hasSize(32);
    assertThat((byte[]) stored.get("code_hash")).hasSize(32);
    assertThat(new String((byte[]) stored.get("token_hash"), StandardCharsets.UTF_8))
        .doesNotContain(token);
    assertThat(new String((byte[]) stored.get("code_hash"), StandardCharsets.UTF_8))
        .doesNotContain(code);
  }

  @Test
  @Criterion({"AC-PAYOUT-005", "AC-PAYOUT-017", "AC-PAYOUT-024"})
  void codesAreSingleUseAttemptLimitedAndResendInvalidates() throws Exception {
    Session session = session("0240000403", "Ama Mensah");
    UUID goalId = createGoal(session, "ALWAYS", "Esi Mensah", APPROVER_PHONE);
    fundGoal(session, goalId, 5_000);

    ResponseEntity<Map<String, Object>> firstRequest = request(session, goalId, 1_000);
    UUID firstPayout = UUID.fromString((String) firstRequest.getBody().get("id"));
    String firstToken = smsToken();
    String firstCode = FakeSmsSender.latestCode();
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(approvalVerify(firstToken, "000000").getStatusCode())
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    assertThat(approvalVerify(firstToken, firstCode).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            jdbc.queryForObject(
                "select state from payouts where id = ?", String.class, firstPayout))
        .isEqualTo("CANCELLED");
    assertThat(count("select count(*) from ledger_entries where payout_id = ?", firstPayout))
        .isZero();

    request(session, goalId, 1_000);
    String secondToken = smsToken();
    Thread.sleep(1_100);
    ResponseEntity<Void> resent = resend(secondToken);
    assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(approvalGet(secondToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    String thirdToken = smsToken();
    String thirdCode = FakeSmsSender.latestCode();
    assertThat(approvalVerify(thirdToken, thirdCode).getStatusCode()).isEqualTo(HttpStatus.OK);

    UUID expiringPayout =
        UUID.fromString((String) request(session, goalId, 1_000).getBody().get("id"));
    String expiringToken = smsToken();
    jdbc.update(
        "update payout_approvals set created_at = now() - interval '2 days', token_expires_at = now() - interval '1 second' where payout_id = ?",
        expiringPayout);
    assertThat(approvals.expire()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select state from payouts where id = ?", String.class, expiringPayout))
        .isEqualTo("CANCELLED");
    assertThat(approvalGet(expiringToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(count("select count(*) from ledger_entries where payout_id = ?", expiringPayout))
        .isZero();
  }

  private UUID createGoal(
      Session session, String secondApprover, String approverName, String approverPhone) {
    FakeAccountResolver.name("0240000409", "Kofi Mensah");
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Ama's funeral");
    body.put("template", "FUNERAL");
    body.put("targetType", "FIXED");
    body.put("fixedAmountPesewas", 5_000L);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", "BENEFICIARY_MOMO");
    body.put("destinationPhone", "0240000409");
    body.put("network", "MTN");
    body.put("secondApprover", secondApprover);
    body.put("secondApproverName", approverName);
    body.put("secondApproverPhone", approverPhone);
    body.put("holdWindowMinutes", 60);
    body.put("surplusRule", "PAY_BENEFICIARY");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "goal-" + UUID.randomUUID());
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    ResponseEntity<Map<String, Object>> created =
        rest.exchange(
            "/organizer/goals",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            new ParameterizedTypeReference<>() {});
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
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
        "approval-" + UUID.randomUUID(),
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
        "item-" + UUID.randomUUID());
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

  private ResponseEntity<Map<String, Object>> approvalGet(String token) {
    return rest.exchange(
        "/payout-approvals/{token}",
        HttpMethod.GET,
        new HttpEntity<>(new HttpHeaders()),
        new ParameterizedTypeReference<>() {},
        token);
  }

  private ResponseEntity<Map<String, Object>> approvalVerify(String token, String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/payout-approvals/{token}/verify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("code", code), headers),
        new ParameterizedTypeReference<>() {},
        token);
  }

  private ResponseEntity<Void> resend(String token) {
    return rest.exchange(
        "/payout-approvals/{token}/resend",
        HttpMethod.POST,
        new HttpEntity<>(new HttpHeaders()),
        Void.class,
        token);
  }

  private ResponseEntity<Map<String, Object>> decline(String token) {
    return rest.exchange(
        "/payout-approvals/{token}/decline",
        HttpMethod.POST,
        new HttpEntity<>(new HttpHeaders()),
        new ParameterizedTypeReference<>() {},
        token);
  }

  private String smsToken() {
    String message = FakeSmsSender.sent().getLast().message();
    Matcher matcher = TOKEN.matcher(message);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
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

  private record Session(String token, UUID userId) {}
}
