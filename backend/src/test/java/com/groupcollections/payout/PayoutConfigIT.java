package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakeSmsSender;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Proves the payout configuration in application.yml is wired through (T306). */
@Import(PayoutConfigIT.ConfigTestConfiguration.class)
@TestPropertySource(
    properties = {
      "collections.payout.enabled=true",
      "collections.payout.approval-ttl=PT2M",
      "collections.payout.token-ttl=PT2H",
      "collections.payout.approval-max-attempts=2",
      "collections.payout.minimum-hold-minutes=3"
    })
class PayoutConfigIT extends ApiIntegrationTest {

  @TestConfiguration
  static class ConfigTestConfiguration {
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
  void configuredMinimumHoldIsEnforcedOnCreationAndSettings() {
    Session session = session("0240000801", "Ama Mensah");

    ResponseEntity<Map<String, Object>> tooShort = createGoal(session, 2, "OFF", null, null);
    assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tooShort.getBody()).isNotNull();
    assertThat(tooShort.getBody().get("code")).isEqualTo("HOLD_REQUIRED");

    UUID goalId =
        UUID.fromString((String) createGoal(session, 3, "OFF", null, null).getBody().get("id"));
    ResponseEntity<Map<String, Object>> tooShortUpdate =
        updateSettings(session, goalId, Map.of("holdWindowMinutes", 2));
    assertThat(tooShortUpdate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(tooShortUpdate.getBody()).isNotNull();
    assertThat(tooShortUpdate.getBody().get("code")).isEqualTo("HOLD_REQUIRED");
  }

  @Test
  void approvalChallengeUsesConfiguredTtlAndAttempts() {
    Session session = session("0240000802", "Ama Mensah");
    UUID goalId =
        UUID.fromString(
            (String)
                createGoal(session, 3, "ALWAYS", "Esi Mensah", "0240000803").getBody().get("id"));
    fundGoal(goalId, 5_000);

    ResponseEntity<Map<String, Object>> payout = request(session, goalId, 1_000);
    assertThat(payout.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(payout.getBody()).isNotNull();
    assertThat(payout.getBody().get("state")).isEqualTo("REQUESTED");

    Map<String, Object> approval =
        jdbc.queryForMap(
            """
            select max_attempts,
                   extract(epoch from (code_expires_at - created_at)) as code_seconds,
                   extract(epoch from (token_expires_at - created_at)) as token_seconds
            from payout_approvals
            where payout_id = (select id from payouts where goal_id = ?) and consumed_at is null
            """,
            goalId);
    assertThat(((Number) approval.get("max_attempts")).intValue()).isEqualTo(2);
    assertThat(((Number) approval.get("code_seconds")).longValue()).isEqualTo(120);
    assertThat(((Number) approval.get("token_seconds")).longValue()).isEqualTo(7200);
  }

  private ResponseEntity<Map<String, Object>> createGoal(
      Session session, int holdMinutes, String approver, String approverName, String phone) {
    FakeAccountResolver.name("0240000809", "Kofi Mensah");
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Config goal");
    body.put("template", "FUNERAL");
    body.put("targetType", "FIXED");
    body.put("fixedAmountPesewas", 5_000L);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", "BENEFICIARY_MOMO");
    body.put("destinationPhone", "0240000809");
    body.put("network", "MTN");
    body.put("secondApprover", approver);
    if (approverName != null) {
      body.put("secondApproverName", approverName);
      body.put("secondApproverPhone", phone);
    }
    body.put("holdWindowMinutes", holdMinutes);
    body.put("surplusRule", "PAY_BENEFICIARY");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "config-" + UUID.randomUUID());
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/goals",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {});
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

  private ResponseEntity<Map<String, Object>> request(Session session, UUID goalId, long amount) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "config-payout-" + UUID.randomUUID());
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/goals/{goalId}/payouts",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("amountPesewas", amount), headers),
        new ParameterizedTypeReference<>() {},
        goalId);
  }

  private void fundGoal(UUID goalId, long amount) {
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
        "config-" + UUID.randomUUID(),
        "cipher".getBytes(StandardCharsets.UTF_8),
        new byte[32],
        "config-fund-" + UUID.randomUUID());
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
        "config-settle-" + UUID.randomUUID(),
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
        "config-item-" + UUID.randomUUID());
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
