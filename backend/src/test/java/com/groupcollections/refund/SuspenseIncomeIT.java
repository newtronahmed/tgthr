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
      "collections.refunds.partner-retains-processing-fee=false",
      "collections.refunds.suspense-income-after=PT1H"
    })
class SuspenseIncomeIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;

  @Test
  @Criterion("AC-OPS-004")
  void agedSuspenseBecomesMiscellaneousIncomeExactlyOnce() {
    SuspenseItem item = suspenseItem("income", 9_000, "0240000011", 2 * 60 * 60);
    String proposerToken = signIn(operator("income.proposer", OperatorRole.RECONCILER));
    TestOperator approver = operator("income.approver", OperatorRole.FINANCE_APPROVER);
    String approverToken = signIn(approver);

    ResponseEntity<Map<String, Object>> proposed =
        proposeIncome(item.id(), "income-propose-one", "unclaimed late payment", proposerToken);

    assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(proposed.getBody())
        .containsEntry("contributionId", item.id().toString())
        .containsEntry("resolution", "INCOME")
        .containsEntry("status", "PROPOSED");
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and entry_type = 'ADJUSTMENT'",
                item.id()))
        .isZero();
    assertThat(suspenseBalance(item.id())).isEqualTo(item.amount());

    ResponseEntity<Map<String, Object>> approved =
        approveIncome(item.id(), "income-approve-one", "approved as income", approverToken, null);

    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(approved.getBody())
        .containsEntry("resolution", "INCOME")
        .containsEntry("status", "APPLIED");
    UUID postingSetId = UUID.fromString((String) approved.getBody().get("postingSetId"));

    assertThat(ledgerEntries(postingSetId))
        .containsExactlyInAnyOrder(
            Map.of(
                "account",
                "SUSPENSE",
                "entry_type",
                "ADJUSTMENT",
                "direction",
                "DEBIT",
                "amount_minor_units",
                item.amount()),
            Map.of(
                "account",
                "PLATFORM_REVENUE",
                "entry_type",
                "ADJUSTMENT",
                "direction",
                "CREDIT",
                "amount_minor_units",
                item.amount()));
    assertThat(suspenseBalance(item.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "select approver_id from reconciliation_resolutions where contribution_id = ?",
                UUID.class,
                item.id()))
        .isEqualTo(approver.id());
    assertThat(
            jdbc.queryForObject(
                "select requires_reconciliation from contributions where id = ?",
                Boolean.class,
                item.id()))
        .isTrue();
    assertThat(pendingQueue(proposerToken))
        .noneMatch(row -> item.id().toString().equals(row.get("contributionId")));

    ResponseEntity<Map<String, Object>> reclassify =
        reclassify(item.id(), "income-reclassify", "try another resolution", proposerToken);
    assertThat(reclassify.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(reclassify.getBody()).containsEntry("code", "RECONCILIATION_ALREADY_RESOLVED");
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-OPS-004")
  void incomeBeforeTheTimeoutIsRejectedWithoutPosting() {
    SuspenseItem item = suspenseItem("income-fresh", 4_000, "0240000012", 60);
    String token = signIn(operator("income.fresh", OperatorRole.RECONCILER));

    ResponseEntity<Map<String, Object>> proposed =
        proposeIncome(item.id(), "income-fresh-one", "too recent", token);

    assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(proposed.getBody()).containsEntry("code", "SUSPENSE_NOT_AGED");
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and entry_type = 'ADJUSTMENT'",
                item.id()))
        .isZero();
  }

  @Test
  @Criterion("AC-OPS-009")
  void theSameOperatorCannotProposeAndApproveIncome() {
    SuspenseItem item = suspenseItem("income-dual", 6_000, "0240000013", 2 * 60 * 60);
    TestOperator dual =
        operator("income.dual", OperatorRole.RECONCILER, OperatorRole.FINANCE_APPROVER);
    String token = signIn(dual);

    assertThat(
            proposeIncome(item.id(), "income-dual-propose", "proposal from the same person", token)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    ResponseEntity<Map<String, Object>> approved =
        approveIncome(item.id(), "income-dual-approve", "self approval", token, null);

    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(approved.getBody()).containsEntry("code", "DUAL_CONTROL_REQUIRED");
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and entry_type = 'ADJUSTMENT'",
                item.id()))
        .isZero();
    assertThat(suspenseBalance(item.id())).isEqualTo(item.amount());
    assertThat(
            jdbc.queryForObject(
                "select status from reconciliation_resolutions where contribution_id = ?",
                String.class,
                item.id()))
        .isEqualTo("PROPOSED");
  }

  @Test
  @Criterion("AC-OPS-009")
  void aProposerWithoutTheFinanceRoleCannotApprove() {
    SuspenseItem item = suspenseItem("income-role", 3_000, "0240000014", 2 * 60 * 60);
    TestOperator proposer = operator("income.role.proposer", OperatorRole.RECONCILER);
    String proposerToken = signIn(proposer);

    assertThat(
            proposeIncome(item.id(), "income-role-propose", "proposal", proposerToken)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    ResponseEntity<Map<String, Object>> approved =
        approveIncome(item.id(), "income-role-approve", "not my role", proposerToken, null);

    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and entry_type = 'ADJUSTMENT'",
                item.id()))
        .isZero();
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

    return new SuspenseItem(contributionId, goalId, amountPesewas);
  }

  private ResponseEntity<Map<String, Object>> proposeIncome(
      UUID contributionId, String key, String reason, String token) {
    return post(contributionId, "/income", key, reason, token, null);
  }

  private ResponseEntity<Map<String, Object>> approveIncome(
      UUID contributionId, String key, String reason, String token, String totpCode) {
    return post(contributionId, "/income/approve", key, reason, token, totpCode);
  }

  private ResponseEntity<Map<String, Object>> reclassify(
      UUID contributionId, String key, String reason, String token) {
    return post(contributionId, "/reclassify", key, reason, token, null);
  }

  private ResponseEntity<Map<String, Object>> post(
      UUID contributionId,
      String action,
      String key,
      String reason,
      String token,
      String totpCode) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", key);
    if (totpCode != null) {
      headers.set("X-Operator-TOTP", totpCode);
    }
    return rest.exchange(
        "/operator/reconciliation/{id}" + action,
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", reason), headers),
        new ParameterizedTypeReference<>() {},
        contributionId);
  }

  private List<Map<String, Object>> pendingQueue(String token) {
    return rest.exchange(
            "/operator/reconciliation/pending",
            HttpMethod.GET,
            new HttpEntity<>(jsonHeaders(token)),
            new ParameterizedTypeReference<List<Map<String, Object>>>() {})
        .getBody();
  }

  private List<Map<String, Object>> ledgerEntries(UUID postingSetId) {
    return jdbc.queryForList(
        """
        select account, entry_type, direction, amount_minor_units
        from ledger_entries where posting_set_id = ?
        """,
        postingSetId);
  }

  private long suspenseBalance(UUID contributionId) {
    Long balance =
        jdbc.queryForObject(
            """
            select coalesce(sum(case when direction = 'CREDIT' then amount_minor_units
                                     else -amount_minor_units end), 0)
            from ledger_entries where account = 'SUSPENSE' and contribution_id = ?
            """,
            Long.class,
            contributionId);
    return balance == null ? 0 : balance;
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

  private record SuspenseItem(UUID id, UUID goalId, long amount) {}

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
