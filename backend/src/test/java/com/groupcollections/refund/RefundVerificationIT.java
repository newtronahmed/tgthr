package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.alerts.Alert;
import com.groupcollections.operator.Base32;
import com.groupcollections.operator.OperatorRole;
import com.groupcollections.operator.OperatorSecretCipher;
import com.groupcollections.operator.OperatorSessionService;
import com.groupcollections.operator.PasswordHasher;
import com.groupcollections.operator.Totp;
import com.groupcollections.payout.PayoutRepository;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.RecordingAlertPublisher;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
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
      "collections.refunds.enabled=true",
      "collections.refunds.provider=paystack",
      "collections.refunds.partner-retains-processing-fee=false",
      "collections.refunds.verification.initial-delay=PT1H",
      "collections.refunds.verification.max-delay=PT2H",
      "collections.refunds.verification.max-attempts=3",
      "collections.refunds.verification.batch-size=10",
      "collections.refunds.verification.scan-ms=3600000"
    })
class RefundVerificationIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;
  @Autowired private PayoutRepository payouts;
  @Autowired private RefundVerificationJob job;

  @Test
  @Criterion("AC-REFUND-011")
  void unwatchedPendingRefundIsRetriedWithBackoffThenEscalated() {
    PAYSTACK.refundScenario("REQUEST_TIMEOUT");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.verification", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-verification-one", "duplicate charge", token);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));

    assertThat(refundState(refundId)).isEqualTo("PROVIDER_PENDING");
    assertThat(attempts(refundId)).isZero();
    assertThat(nextVerification(refundId))
        .isAfter(Instant.now().plusSeconds(3_000))
        .isBefore(Instant.now().plusSeconds(3_700));
    assertThat(alertCount(Alert.AlertType.REFUND_PROVIDER_PENDING)).isEqualTo(1);

    job.run();
    assertThat(attempts(refundId)).isZero();
    assertThat(refundState(refundId)).isEqualTo("PROVIDER_PENDING");

    PAYSTACK.refundScenario("SERVER_ERROR");
    dueNow(refundId);
    job.run();

    assertThat(attempts(refundId)).isEqualTo(1);
    assertThat(refundState(refundId)).isEqualTo("PROVIDER_PENDING");
    assertThat(nextVerification(refundId))
        .isAfter(Instant.now().plusSeconds(3_000))
        .isBefore(Instant.now().plusSeconds(3_700));
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isEqualTo(contribution.amount());
    assertThat(count("select count(*) from ledger_entries where refund_id = ?", refundId)).isZero();
    assertThat(alertCount(Alert.AlertType.REFUND_PROVIDER_PENDING)).isEqualTo(1);
    assertThat(lastAttemptDetail(refundId)).contains("UNKNOWN_STATUS");

    dueNow(refundId);
    job.run();

    assertThat(attempts(refundId)).isEqualTo(2);
    assertThat(nextVerification(refundId))
        .isAfter(Instant.now().plusSeconds(7_000))
        .isBefore(Instant.now().plusSeconds(7_300));

    dueNow(refundId);
    job.run();

    assertThat(attempts(refundId)).isEqualTo(3);
    assertThat(refundState(refundId)).isEqualTo("PROVIDER_PENDING");
    assertThat(exhaustedAt(refundId)).isNotNull();
    assertThat(nextVerification(refundId)).isNull();
    assertThat(alertCount(Alert.AlertType.REFUND_VERIFICATION_EXHAUSTED)).isEqualTo(1);
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isEqualTo(contribution.amount());
    assertThat(count("select count(*) from ledger_entries where refund_id = ?", refundId)).isZero();
    assertThat(attemptEvents(refundId)).isEqualTo(3);
    assertThat(exhaustedEvents(refundId)).isEqualTo(1);

    // An operator check that is still pending re-arms the job: exhaustion is an escalation, not a
    // dead end, and the provider may answer days later (ADR 0020).
    ResponseEntity<Map<String, Object>> stillPending =
        verify(refundId, "provider verification", token);
    assertThat(stillPending.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refundState(refundId)).isEqualTo("PROVIDER_PENDING");
    assertThat(exhaustedAt(refundId)).isNull();
    assertThat(attempts(refundId)).isZero();
    assertThat(nextVerification(refundId))
        .isAfter(Instant.now().plusSeconds(3_000))
        .isBefore(Instant.now().plusSeconds(3_700));
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isEqualTo(contribution.amount());
    assertThat(count("select count(*) from ledger_entries where refund_id = ?", refundId)).isZero();
    assertThat(alertCount(Alert.AlertType.REFUND_PROVIDER_PENDING)).isEqualTo(1);

    PAYSTACK.refundScenario("SUCCESS");
    ResponseEntity<Map<String, Object>> verified = verify(refundId, "manual verification", token);

    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refundState(refundId)).isEqualTo("SUCCEEDED");
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and entry_type = 'REVERSAL'",
                refundId))
        .isEqualTo(4);
    // Exhaustion was cleared by the re-arm, so it is history in the event log, not a live column.
    assertThat(exhaustedAt(refundId)).isNull();
    assertThat(exhaustedEvents(refundId)).isEqualTo(1);
  }

  private Contribution confirmedContribution() {
    String slug = insertGoal("refund-verification", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 10_000, "0240000015").getBody();
    String contributionId = (String) created.get("id");
    long total = ((Number) created.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total)).isEqualTo(204);
    UUID id = UUID.fromString(contributionId);
    UUID goalId =
        jdbc.queryForObject("select goal_id from contributions where id = ?", UUID.class, id);
    return new Contribution(
        id,
        goalId,
        ((Number) created.get("amountPesewas")).longValue(),
        ((Number) created.get("totalPesewas")).longValue());
  }

  private ResponseEntity<Map<String, Object>> request(
      UUID contributionId, String key, String reason, String token) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", key);
    return rest.exchange(
        "/operator/contributions/{id}/refund",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", reason), headers),
        new ParameterizedTypeReference<>() {},
        contributionId);
  }

  private ResponseEntity<Map<String, Object>> verify(UUID refundId, String reason, String token) {
    return rest.exchange(
        "/operator/refunds/{id}/verify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", reason), jsonHeaders(token)),
        new ParameterizedTypeReference<>() {},
        refundId);
  }

  private void dueNow(UUID refundId) {
    jdbc.update("update refunds set next_verification_at = now() where id = ?", refundId);
  }

  private String refundState(UUID refundId) {
    return jdbc.queryForObject("select state from refunds where id = ?", String.class, refundId);
  }

  private int attempts(UUID refundId) {
    Integer value =
        jdbc.queryForObject(
            "select verification_attempts from refunds where id = ?", Integer.class, refundId);
    return value == null ? 0 : value;
  }

  private Instant nextVerification(UUID refundId) {
    OffsetDateTime value =
        jdbc.queryForObject(
            "select next_verification_at from refunds where id = ?",
            OffsetDateTime.class,
            refundId);
    return value == null ? null : value.toInstant();
  }

  private Instant exhaustedAt(UUID refundId) {
    OffsetDateTime value =
        jdbc.queryForObject(
            "select verification_exhausted_at from refunds where id = ?",
            OffsetDateTime.class,
            refundId);
    return value == null ? null : value.toInstant();
  }

  private String lastAttemptDetail(UUID refundId) {
    return jdbc.queryForObject(
        """
        select detail from refund_events
        where refund_id = ? and event = 'VERIFICATION_ATTEMPT'
        order by id desc limit 1
        """,
        String.class,
        refundId);
  }

  private long attemptEvents(UUID refundId) {
    return count(
        "select count(*) from refund_events where refund_id = ? and event = 'VERIFICATION_ATTEMPT'",
        refundId);
  }

  private long exhaustedEvents(UUID refundId) {
    return count(
        "select count(*) from refund_events where refund_id = ? and event = 'VERIFICATION_EXHAUSTED'",
        refundId);
  }

  private long alertCount(Alert.AlertType type) {
    return RecordingAlertPublisher.alerts().stream().filter(alert -> alert.type() == type).count();
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
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
