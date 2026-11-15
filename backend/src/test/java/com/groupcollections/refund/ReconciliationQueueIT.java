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
      "collections.refunds.enabled=true",
      "collections.refunds.provider=paystack",
      "collections.refunds.suspense-income-after=P30D"
    })
class ReconciliationQueueIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;

  @Test
  @Criterion("AC-OPS-001")
  void queueShowsAmountAgeGoalAndReferenceWithoutPhones() {
    SuspenseItem older = suspenseItem("queue-older", 10_000, "0240000001", 2 * 60 * 60);
    SuspenseItem newer = suspenseItem("queue-newer", 5_000, "0240000002", 60);
    String token = signIn(operator("queue.reader", OperatorRole.RECONCILER));

    ResponseEntity<List<Map<String, Object>>> response =
        rest.exchange(
            "/operator/reconciliation/pending",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token)),
            new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    List<Map<String, Object>> queue = response.getBody();
    Map<String, Object> first = row(queue, older.id());
    Map<String, Object> second = row(queue, newer.id());
    assertThat(queue.indexOf(first)).isLessThan(queue.indexOf(second));

    assertThat(first)
        .containsEntry("contributionId", older.id().toString())
        .containsEntry("goalId", older.goalId().toString())
        .containsEntry("goalTitle", "Nyame biribi")
        .containsEntry("currencyCode", "GHS")
        .containsEntry("providerReference", older.id().toString());
    assertThat(((Number) first.get("amountMinorUnits")).longValue()).isEqualTo(10_000);
    assertThat(((Number) first.get("ageSeconds")).longValue()).isBetween(7_100L, 7_300L);
    assertThat(first.get("suspendedAt")).isNotNull();
    assertThat(first.keySet()).noneMatch(key -> key.toLowerCase().contains("phone"));

    assertThat(second).containsEntry("contributionId", newer.id().toString());
    assertThat(((Number) second.get("amountMinorUnits")).longValue()).isEqualTo(5_000);
    assertThat(((Number) second.get("ageSeconds")).longValue()).isBetween(0L, 300L);
  }

  @Test
  @Criterion("AC-OPS-001")
  void queueNeverSerializesPayerContactDetails() {
    suspenseItem("queue-phones", 7_500, "0240000003", 30);
    String token = signIn(operator("queue.phone.check", OperatorRole.RECONCILER));

    ResponseEntity<String> response =
        rest.exchange(
            "/operator/reconciliation/pending",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token)),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .doesNotContain("phone")
        .doesNotContain("momo")
        .doesNotContain("0240000003");
  }

  private SuspenseItem suspenseItem(
      String slugPrefix, long amountPesewas, String momoNumber, long ageSeconds) {
    String slug = insertGoal(slugPrefix, "OPEN", null);
    Map<String, Object> created = createContribution(slug, amountPesewas, momoNumber).getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    UUID goalId =
        jdbc.queryForObject(
            "select goal_id from contributions where id = ?", UUID.class, contributionId);
    long total = ((Number) created.get("totalPesewas")).longValue();

    jdbc.update("update goals set status = 'CLOSED' where id = ?", goalId);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    jdbc.update(
        "update contributions set updated_at = ? where id = ?",
        Timestamp.from(Instant.now().minusSeconds(ageSeconds)),
        contributionId);

    return new SuspenseItem(contributionId, goalId);
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

  private static Map<String, Object> row(List<Map<String, Object>> queue, UUID contributionId) {
    return queue.stream()
        .filter(item -> contributionId.toString().equals(item.get("contributionId")))
        .findFirst()
        .orElseThrow();
  }

  private record SuspenseItem(UUID id, UUID goalId) {}

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
