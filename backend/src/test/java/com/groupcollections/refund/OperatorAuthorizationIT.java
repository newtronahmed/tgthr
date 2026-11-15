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
      "collections.refunds.suspense-income-after=PT1H"
    })
class OperatorAuthorizationIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;

  @Test
  @Criterion("AC-OPS-005")
  void refundAndReconciliationRolesAreSeparated() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution();
    String admin = signIn(operator("authz.admin", OperatorRole.OPERATOR_ADMIN));
    String finance = signIn(operator("authz.finance", OperatorRole.FINANCE_APPROVER));
    String reconciler = signIn(operator("authz.reconciler", OperatorRole.RECONCILER));
    String refundOperator = signIn(operator("authz.refund", OperatorRole.REFUND_OPERATOR));

    assertThat(refund(contribution.id(), "authz-refund-admin", admin).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(refund(contribution.id(), "authz-refund-finance", finance).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(refund(contribution.id(), "authz-refund-reconciler", reconciler).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(refund(contribution.id(), "authz-refund-operator", refundOperator).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    SuspenseItem item = suspenseItem("authz-reconcile", 6_000, "0240000011");
    assertThat(reclassify(item.id(), "authz-reclassify-refund", refundOperator).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(reclassify(item.id(), "authz-reclassify-finance", finance).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(reclassify(item.id(), "authz-reclassify-admin", admin).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(reclassify(item.id(), "authz-reclassify-reconciler", reconciler).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @Criterion({"AC-OPS-005", "AC-OPS-009"})
  void incomeProposalAndApprovalUseDifferentRolesAndPeople() {
    SuspenseItem item = agedSuspenseItem("authz-income", 7_000, "0240000012");
    TestOperator proposer = operator("authz.income.proposer", OperatorRole.RECONCILER);
    TestOperator approver = operator("authz.income.approver", OperatorRole.FINANCE_APPROVER);
    String proposerToken = signIn(proposer);
    String approverToken = signIn(approver);
    String financeOnly = signIn(operator("authz.income.finance", OperatorRole.FINANCE_APPROVER));

    assertThat(
            post(item.id(), "/income", "authz-income-wrong-role", "proposal", financeOnly)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            post(item.id(), "/income", "authz-income-propose", "aged suspense", proposerToken)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    assertThat(
            post(
                    item.id(),
                    "/income/approve",
                    "authz-income-approve-wrong-role",
                    "approval",
                    proposerToken)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    assertThat(
            post(item.id(), "/income/approve", "authz-income-approve", "approval", approverToken)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ? and status = 'APPLIED'",
                item.id()))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-OPS-005")
  void operatorActionsRequireAnIdempotencyKeyAndReason() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution();
    SuspenseItem item = suspenseItem("authz-inputs", 4_000, "0240000013");
    String token =
        signIn(operator("authz.inputs", OperatorRole.REFUND_OPERATOR, OperatorRole.RECONCILER));

    assertThat(postWithoutKey(contribution.id(), token).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(postWithBlankReason(contribution.id(), token).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(count("select count(*) from refunds where contribution_id = ?", contribution.id()))
        .isZero();

    assertThat(reconciliationWithoutKey(item.id(), token).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(reconciliationWithBlankReason(item.id(), token).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
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
  }

  @Test
  @Criterion("AC-REFUND-008")
  void unknownContributionsAreNotFound() {
    String token =
        signIn(operator("authz.unknown", OperatorRole.REFUND_OPERATOR, OperatorRole.RECONCILER));
    UUID unknown = UUID.randomUUID();

    ResponseEntity<Map<String, Object>> refund = refund(unknown, "authz-unknown-refund", token);
    assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(refund.getBody()).containsEntry("code", "CONTRIBUTION_NOT_FOUND");

    ResponseEntity<Map<String, Object>> reclassify =
        reclassify(unknown, "authz-unknown-reclassify", token);
    assertThat(reclassify.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(reclassify.getBody()).containsEntry("code", "CONTRIBUTION_NOT_FOUND");
  }

  private Contribution confirmedContribution() {
    String slug = insertGoal("authz-refund", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 10_000, "0240000001").getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    UUID goalId =
        jdbc.queryForObject(
            "select goal_id from contributions where id = ?", UUID.class, contributionId);
    return new Contribution(contributionId, goalId);
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

  private SuspenseItem agedSuspenseItem(String slugPrefix, long amountPesewas, String momoNumber) {
    SuspenseItem item = suspenseItem(slugPrefix, amountPesewas, momoNumber);
    jdbc.update(
        "update contributions set updated_at = ? where id = ?",
        Timestamp.from(Instant.now().minusSeconds(2 * 60 * 60)),
        item.id());
    return item;
  }

  private ResponseEntity<Map<String, Object>> refund(
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

  private ResponseEntity<Map<String, Object>> reclassify(
      UUID contributionId, String key, String token) {
    return post(contributionId, "/reclassify", key, "late payment", token);
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

  private ResponseEntity<Map<String, Object>> postWithoutKey(UUID contributionId, String token) {
    return rest.exchange(
        "/operator/contributions/{id}/refund",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "duplicate charge"), jsonHeaders(token)),
        new ParameterizedTypeReference<>() {},
        contributionId);
  }

  private ResponseEntity<Map<String, Object>> postWithBlankReason(
      UUID contributionId, String token) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", "authz-blank-" + UUID.randomUUID());
    return rest.exchange(
        "/operator/contributions/{id}/refund",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", " "), headers),
        new ParameterizedTypeReference<>() {},
        contributionId);
  }

  private ResponseEntity<Map<String, Object>> reconciliationWithoutKey(
      UUID contributionId, String token) {
    return rest.exchange(
        "/operator/reconciliation/{id}/reclassify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "late payment"), jsonHeaders(token)),
        new ParameterizedTypeReference<>() {},
        contributionId);
  }

  private ResponseEntity<Map<String, Object>> reconciliationWithBlankReason(
      UUID contributionId, String token) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", "authz-blank-" + UUID.randomUUID());
    return rest.exchange(
        "/operator/reconciliation/{id}/reclassify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", " "), headers),
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

  private record Contribution(UUID id, UUID goalId) {}

  private record SuspenseItem(UUID id, UUID goalId) {}

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
