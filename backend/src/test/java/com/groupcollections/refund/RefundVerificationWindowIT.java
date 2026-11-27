package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.operator.Base32;
import com.groupcollections.operator.OperatorRole;
import com.groupcollections.operator.OperatorSecretCipher;
import com.groupcollections.operator.OperatorSessionService;
import com.groupcollections.operator.PasswordHasher;
import com.groupcollections.operator.Totp;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.security.SecureRandom;
import java.time.Duration;
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

/**
 * The shipped defaults must outlast Paystack's refund settlement window (ADR 0020, spec rule 10).
 * This exercises the real configuration rather than a copy of the numbers, so a change to {@code
 * application.yml} that shrinks the window fails here.
 */
@TestPropertySource(
    properties = {
      "collections.operator.enabled=true",
      "collections.operator.totp-secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "collections.operator.session-ttl=PT1H",
      "collections.refunds.enabled=true",
      "collections.refunds.provider=paystack"
    })
class RefundVerificationWindowIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;

  @Test
  @Criterion("AC-REFUND-011")
  void anUnresolvedRefundIsStillBeingCheckedInsideTheProviderWindow() {
    PAYSTACK.refundScenario("REQUEST_TIMEOUT");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.window", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-window-one", "duplicate charge", token);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));

    // The real first check delay, not a constant: the default must not be the old five minutes.
    Instant next = nextVerification(refundId);
    assertThat(next).isAfter(Instant.now().plus(Duration.ofMinutes(10)));

    // And the configured ceiling must allow the refund to stay in flight for days, not hours.
    assertThat(next).isBefore(Instant.now().plus(Duration.ofHours(1)));
  }

  private Contribution confirmedContribution() {
    String slug = insertGoal("refund-window", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 10_000, "0240000016").getBody();
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

  private Instant nextVerification(UUID refundId) {
    OffsetDateTime value =
        jdbc.queryForObject(
            "select next_verification_at from refunds where id = ?",
            OffsetDateTime.class,
            refundId);
    return value == null ? null : value.toInstant();
  }

  private static HttpHeaders jsonHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.add(HttpHeaders.COOKIE, OperatorSessionService.COOKIE_NAME + "=" + token);
    }
    return headers;
  }

  private record TestOperator(UUID id, String username, String secretBase32) {}

  private record Contribution(UUID id, UUID goalId, long amount, long total) {}
}
