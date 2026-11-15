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
import com.groupcollections.support.RecordingAlertPublisher;
import java.security.SecureRandom;
import java.time.Instant;
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
      "collections.refunds.partner-retains-processing-fee=false"
    })
class OperatorPrivacyIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";
  private static final String PAYER_PHONE = "0240000001";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;

  @Test
  @Criterion("AC-REFUND-008")
  void refundSurfacesNeverCarryThePayerPhone() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution(PAYER_PHONE);
    String token = signIn(operator("privacy.refund", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "privacy-refund-one", "duplicate charge", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody().toString()).doesNotContain(PAYER_PHONE).doesNotContain("phone");
    assertThat(
            jdbc.queryForList(
                "select reason from refunds where contribution_id = ?",
                String.class,
                contribution.id()))
        .noneMatch(text -> text.contains(PAYER_PHONE));
    assertThat(
            jdbc.queryForList(
                """
                select detail from refund_events
                where refund_id = (select id from refunds where contribution_id = ?)
                """,
                String.class,
                contribution.id()))
        .noneMatch(text -> text != null && text.contains(PAYER_PHONE));
    assertThat(
            jdbc.queryForList(
                "select reason from operator_actions where subject_id = ?",
                String.class,
                contribution.id()))
        .noneMatch(text -> text.contains(PAYER_PHONE));
    assertThat(RecordingAlertPublisher.alerts())
        .allSatisfy(
            alert ->
                assertThat(alert.reference() + " " + alert.detail()).doesNotContain(PAYER_PHONE));
    assertThat(meterRegistry.getMeters())
        .allSatisfy(
            meter -> {
              assertThat(meter.getId().getName()).doesNotContain(PAYER_PHONE);
              assertThat(meter.getId().getTags())
                  .noneMatch(tag -> tag.getValue().contains(PAYER_PHONE));
            });
  }

  @Test
  @Criterion("AC-REFUND-008")
  void phoneNumbersInOperatorReasonsAreRejectedBeforeAnyWrite() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution(PAYER_PHONE);
    String refundToken = signIn(operator("privacy.refund.reason", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> refundResponse =
        request(
            contribution.id(),
            "privacy-refund-phone",
            "payer called from " + PAYER_PHONE,
            refundToken);

    assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(refundResponse.getBody()).containsEntry("code", "INVALID_REFUND");
    assertThat(count("select count(*) from refunds where contribution_id = ?", contribution.id()))
        .isZero();
    assertThat(
            count(
                "select count(*) from operator_actions where subject_id = ? and action = 'REFUND_REQUESTED'",
                contribution.id()))
        .isZero();
    assertThat(PAYSTACK.refundBodies()).isEmpty();

    SuspenseItem item = suspenseItem("privacy-suspense", 5_000, "0240000002");
    String reconcilerToken = signIn(operator("privacy.reconciler", OperatorRole.RECONCILER));

    ResponseEntity<Map<String, Object>> reclassify =
        reclassify(
            item.id(), "privacy-reclassify-phone", "payer +233 24 000 0002", reconcilerToken);

    assertThat(reclassify.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(reclassify.getBody()).containsEntry("code", "INVALID_RECONCILIATION");
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and account = 'GOAL_POOL'",
                item.id()))
        .isZero();

    ResponseEntity<Map<String, Object>> income =
        post(item.id(), "/income", "privacy-income-phone", "payer 024 000 0002", reconcilerToken);

    assertThat(income.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(income.getBody()).containsEntry("code", "INVALID_RECONCILIATION");
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isZero();
  }

  @Test
  @Criterion("AC-REFUND-008")
  void aVerificationReasonCannotCarryAPhoneNumberEither() {
    PAYSTACK.refundScenario("SERVER_ERROR");
    Contribution contribution = confirmedContribution(PAYER_PHONE);
    String token = signIn(operator("privacy.verify.reason", OperatorRole.REFUND_OPERATOR));
    UUID refundId =
        UUID.fromString(
            (String)
                request(contribution.id(), "privacy-verify-one", "duplicate charge", token)
                    .getBody()
                    .get("id"));
    long events = count("select count(*) from refund_events where refund_id = ?", refundId);

    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/operator/refunds/{id}/verify",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "confirm with " + PAYER_PHONE), jsonHeaders(token)),
            new ParameterizedTypeReference<>() {},
            refundId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).containsEntry("code", "INVALID_REFUND");
    assertThat(
            jdbc.queryForObject("select state from refunds where id = ?", String.class, refundId))
        .isEqualTo("PROVIDER_PENDING");
    assertThat(count("select count(*) from refund_events where refund_id = ?", refundId))
        .isEqualTo(events);
  }

  private Contribution confirmedContribution(String momoNumber) {
    String slug = insertGoal("privacy-refund", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 10_000, momoNumber).getBody();
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

  private SuspenseItem suspenseItem(String slugPrefix, long amountPesewas, String momoNumber) {
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
    return new SuspenseItem(contributionId, goalId);
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

  private ResponseEntity<Map<String, Object>> reclassify(
      UUID contributionId, String key, String reason, String token) {
    return post(contributionId, "/reclassify", key, reason, token);
  }

  private ResponseEntity<Map<String, Object>> post(
      UUID contributionId, String action, String key, String reason, String token) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", key);
    return rest.exchange(
        "/operator/reconciliation/{id}" + action,
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", reason), headers),
        new ParameterizedTypeReference<>() {},
        contributionId);
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

  private record SuspenseItem(UUID id, UUID goalId) {}

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
