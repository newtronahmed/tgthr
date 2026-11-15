package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.operator.Base32;
import com.groupcollections.operator.OperatorRole;
import com.groupcollections.operator.OperatorSecretCipher;
import com.groupcollections.operator.OperatorSessionService;
import com.groupcollections.operator.PasswordHasher;
import com.groupcollections.operator.Totp;
import com.groupcollections.payout.PayoutRepository;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "collections.operator.enabled=true",
      "collections.operator.totp-secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "collections.operator.session-ttl=PT1H",
      "collections.refunds.provider=paystack",
      "collections.refunds.partner-retains-processing-fee=false"
    })
class RefundDisableIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;
  @Autowired private PayoutRepository payouts;
  @Autowired private RefundVerificationJob job;

  @Test
  @Criterion("AC-REFUND-004")
  void disablingNewRequestsKeepsReadAndVerificationOpen() {
    Contribution contribution = confirmedContribution("0240000001");
    TestOperator operator = operator("disabled.verify", OperatorRole.REFUND_OPERATOR);
    String token = signIn(operator);

    ResponseEntity<List<Map<String, Object>>> list =
        rest.exchange(
            "/operator/refunds",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token)),
            new ParameterizedTypeReference<>() {});
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map<String, Object>> blocked =
        request(contribution.id(), "disabled-refund-one", token);
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(blocked.getBody()).containsEntry("code", "REFUNDS_DISABLED");
    assertThat(count("select count(*) from refunds where contribution_id = ?", contribution.id()))
        .isZero();
    assertThat(PAYSTACK.refundBodies()).isEmpty();

    UUID refundId = seedPendingRefund(contribution, operator.id(), null);
    PAYSTACK.seedRefund(
        contribution.id().toString(), "refund-seeded-verify", contribution.total(), "GHS");
    PAYSTACK.refundScenario("SUCCESS");
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isEqualTo(contribution.amount());

    ResponseEntity<Map<String, Object>> verified = verify(refundId, token);

    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refundState(refundId)).isEqualTo("SUCCEEDED");
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and entry_type = 'REVERSAL'",
                refundId))
        .isEqualTo(4);
    assertThat(
            rest.exchange(
                    "/operator/refunds/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(jsonHeaders(token)),
                    new ParameterizedTypeReference<Map<String, Object>>() {},
                    refundId)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @Criterion("AC-REFUND-004")
  void disablingNewRequestsKeepsTheVerificationJobRunning() {
    Contribution contribution = confirmedContribution("0240000002");
    TestOperator operator = operator("disabled.job", OperatorRole.REFUND_OPERATOR);
    UUID refundId = seedPendingRefund(contribution, operator.id(), Instant.now());
    PAYSTACK.seedRefund(
        contribution.id().toString(), "refund-seeded-job", contribution.total(), "GHS");
    PAYSTACK.refundScenario("SUCCESS");

    job.run();

    assertThat(refundState(refundId)).isEqualTo("SUCCEEDED");
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and entry_type = 'REVERSAL'",
                refundId))
        .isEqualTo(4);
  }

  private UUID seedPendingRefund(Contribution contribution, UUID operatorId, Instant nextCheck) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
            select goal_id, amount_minor_units, platform_fee_minor_units,
                   processing_fee_minor_units, total_minor_units, currency_code
            from contributions where id = ?
            """,
            contribution.id());
    UUID refundId = UUID.randomUUID();
    jdbc.update(
        """
        insert into refunds
            (id, contribution_id, goal_id, source_account, amount_minor_units,
             platform_fee_minor_units, processing_fee_minor_units, total_minor_units,
             currency_code, state, idempotency_key, provider_transaction_reference,
             operator_id, reason, created_at, updated_at, verification_attempts,
             next_verification_at)
        values (?, ?, ?, 'GOAL_POOL', ?, ?, ?, ?, ?, 'PROVIDER_PENDING', ?, ?, ?, 'seeded pending',
                now(), now(), 0, ?)
        """,
        refundId,
        contribution.id(),
        row.get("goal_id"),
        row.get("amount_minor_units"),
        row.get("platform_fee_minor_units"),
        row.get("processing_fee_minor_units"),
        row.get("total_minor_units"),
        row.get("currency_code"),
        "seeded-" + refundId,
        contribution.id().toString(),
        operatorId,
        nextCheck == null ? null : Timestamp.from(nextCheck));
    return refundId;
  }

  private ResponseEntity<Map<String, Object>> request(
      UUID contributionId, String key, String token) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", key);
    return rest.exchange(
        "/operator/contributions/{id}/refund",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "duplicate charge"), headers),
        new ParameterizedTypeReference<>() {},
        contributionId);
  }

  private ResponseEntity<Map<String, Object>> verify(UUID refundId, String token) {
    return rest.exchange(
        "/operator/refunds/{id}/verify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "provider verification"), jsonHeaders(token)),
        new ParameterizedTypeReference<>() {},
        refundId);
  }

  private String refundState(UUID refundId) {
    return jdbc.queryForObject("select state from refunds where id = ?", String.class, refundId);
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private Contribution confirmedContribution(String momoNumber) {
    String slug = insertGoal("disabled-refund", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 10_000, momoNumber).getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    UUID goalId =
        jdbc.queryForObject(
            "select goal_id from contributions where id = ?", UUID.class, contributionId);
    return new Contribution(
        contributionId,
        goalId,
        ((Number) created.get("amountPesewas")).longValue(),
        ((Number) created.get("totalPesewas")).longValue());
  }

  private TestOperator operator(String username, OperatorRole... roles) {
    byte[] secret = new byte[20];
    new SecureRandom().nextBytes(secret);
    String encodedSecret = Base32.encode(secret);
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into operators
            (id, username, password_hash, totp_secret_ciphertext, status, created_at, updated_at)
        values (?, ?, ?, ?, 'ACTIVE', now(), now())
        """,
        id,
        username,
        hasher.hash(OPERATOR_PASSWORD),
        cipher.encrypt(encodedSecret));
    for (OperatorRole role : roles) {
      jdbc.update(
          "insert into operator_roles (operator_id, role, granted_at) values (?, ?, now())",
          id,
          role.name());
    }
    return new TestOperator(id, username, encodedSecret);
  }

  private String signIn(TestOperator operator) {
    ResponseEntity<Map<String, Object>> login =
        rest.exchange(
            "/operator/auth/login",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("username", operator.username(), "password", OPERATOR_PASSWORD),
                jsonHeaders(null)),
            new ParameterizedTypeReference<>() {});
    ResponseEntity<Map<String, Object>> mfa =
        rest.exchange(
            "/operator/auth/mfa",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "challengeToken",
                    login.getBody().get("challengeToken"),
                    "code",
                    Totp.code(Base32.decode(operator.secretBase32()), Instant.now())),
                jsonHeaders(null)),
            new ParameterizedTypeReference<>() {});
    assertThat(mfa.getStatusCode()).isEqualTo(HttpStatus.OK);
    String setCookie = mfa.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    String prefix = OperatorSessionService.COOKIE_NAME + "=";
    int start = setCookie.indexOf(prefix);
    int end = setCookie.indexOf(';', start);
    return setCookie.substring(start + prefix.length(), end < 0 ? setCookie.length() : end);
  }

  private static HttpHeaders jsonHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.add(HttpHeaders.COOKIE, OperatorSessionService.COOKIE_NAME + "=" + token);
    }
    return headers;
  }

  private record Contribution(UUID id, UUID goalId, long amount, long total) {}

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
