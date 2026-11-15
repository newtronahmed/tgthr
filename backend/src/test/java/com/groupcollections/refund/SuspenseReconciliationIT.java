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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
      "collections.refunds.suspense-income-after=P30D"
    })
class SuspenseReconciliationIT extends ApiIntegrationTest {

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;
  @Autowired private PayoutRepository payouts;

  @Test
  @Criterion("AC-OPS-002")
  void reclassificationMovesSuspenseToTheGoalPoolExactlyOnce() {
    SuspenseItem item = suspenseItem("reconcile", 10_000, "0240000001");
    String token = signIn(operator("reconcile.worker", OperatorRole.RECONCILER));

    ResponseEntity<Map<String, Object>> first =
        reclassify(item.id(), "reclassify-one", "late payment belongs to the goal", token);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody())
        .containsEntry("contributionId", item.id().toString())
        .containsEntry("resolution", "RECLASSIFY")
        .containsEntry("status", "APPLIED");
    UUID postingSetId = UUID.fromString((String) first.getBody().get("postingSetId"));

    assertThat(ledgerEntries(postingSetId))
        .containsExactlyInAnyOrder(
            Map.of(
                "account", "SUSPENSE",
                "entry_type", "CONTRIBUTION",
                "direction", "DEBIT",
                "amount_minor_units", item.amount()),
            Map.of(
                "account", "GOAL_POOL",
                "entry_type", "CONTRIBUTION",
                "direction", "CREDIT",
                "amount_minor_units", item.amount()));
    assertThat(suspenseBalance(item.id())).isZero();
    assertThat(goalRaised(item.goalId())).isEqualTo(item.amount());
    assertThat(
            jdbc.queryForObject(
                "select requires_reconciliation from contributions where id = ?",
                Boolean.class,
                item.id()))
        .isFalse();
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isEqualTo(1);
    assertThat(pendingQueue(token))
        .noneMatch(row -> item.id().toString().equals(row.get("contributionId")));

    ResponseEntity<Map<String, Object>> replay =
        reclassify(item.id(), "reclassify-one", "late payment belongs to the goal", token);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody()).containsEntry("postingSetId", postingSetId.toString());
    assertThat(count("select count(*) from ledger_entries where posting_set_id = ?", postingSetId))
        .isEqualTo(2);

    ResponseEntity<Map<String, Object>> conflict =
        reclassify(item.id(), "reclassify-two", "a different reason", token);
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody()).containsEntry("code", "RECONCILIATION_ALREADY_RESOLVED");
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-OPS-003")
  void suspenseRefundReversesTheSuspenseAmountAndLeavesNoGoalPoolCredit() {
    PAYSTACK.refundScenario("SUCCESS");
    SuspenseItem item = suspenseItem("suspense-refund", 10_000, "0240000002");
    String token = signIn(operator("reconcile.refunder", OperatorRole.RECONCILER));

    ResponseEntity<Map<String, Object>> response =
        refund(item.id(), "suspense-refund-one", "contributor asked for the money back", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));
    assertThat(response.getBody())
        .containsEntry("contributionId", item.id().toString())
        .containsEntry("sourceAccount", "SUSPENSE");
    assertThat(
            jdbc.queryForObject("select state from refunds where id = ?", String.class, refundId))
        .isEqualTo("SUCCEEDED");

    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, item.id()))
        .isEqualTo("REFUNDED");
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and account = 'GOAL_POOL'",
                refundId))
        .isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and account = 'SUSPENSE' and direction = 'DEBIT' and amount_minor_units = ?",
                refundId,
                item.amount()))
        .isEqualTo(1);
    assertThat(suspenseBalance(item.id())).isZero();
    assertThat(goalRaised(item.goalId())).isZero();
    assertThat(reservedForGoal(item.goalId())).isZero();
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select resolution from reconciliation_resolutions where contribution_id = ?",
                String.class,
                item.id()))
        .isEqualTo("REFUND");
  }

  @Test
  @Criterion("AC-OPS-003")
  void suspenseRefundDoesNotReserveGoalAvailability() {
    PAYSTACK.refundScenario("SERVER_ERROR");
    SuspenseItem item = suspenseItem("suspense-reserve", 8_000, "0240000004");
    String token =
        signIn(
            operator("reconcile.pending", OperatorRole.RECONCILER, OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        refund(item.id(), "suspense-pending-one", "refund is pending", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));
    assertThat(
            jdbc.queryForObject("select state from refunds where id = ?", String.class, refundId))
        .isEqualTo("PROVIDER_PENDING");
    assertThat(payouts.reservedMinorUnits(item.goalId())).isZero();
    assertThat(count("select count(*) from ledger_entries where refund_id = ?", refundId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, item.id()))
        .isEqualTo("CONFIRMED");

    PAYSTACK.refundScenario("SUCCESS");
    ResponseEntity<Map<String, Object>> verified =
        rest.exchange(
            "/operator/refunds/{id}/verify",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "provider verification"), jsonHeaders(token)),
            new ParameterizedTypeReference<>() {},
            refundId);

    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            jdbc.queryForObject("select state from refunds where id = ?", String.class, refundId))
        .isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, item.id()))
        .isEqualTo("REFUNDED");
    assertThat(suspenseBalance(item.id())).isZero();
  }

  @Test
  @Criterion("AC-OPS-006")
  void concurrentResolutionsHaveOneWinner() throws Exception {
    SuspenseItem item = suspenseItem("reconcile-race", 5_000, "0240000003");
    String token = signIn(operator("reconcile.race", OperatorRole.RECONCILER));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<Integer> statuses;
    try {
      Future<Integer> first =
          executor.submit(
              () -> {
                start.await();
                return reclassify(item.id(), "race-one", "first resolver", token)
                    .getStatusCode()
                    .value();
              });
      Future<Integer> second =
          executor.submit(
              () -> {
                start.await();
                return reclassify(item.id(), "race-two", "second resolver", token)
                    .getStatusCode()
                    .value();
              });
      start.countDown();
      statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(
            count(
                "select count(*) from reconciliation_resolutions where contribution_id = ?",
                item.id()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and entry_type = 'CONTRIBUTION' and direction = 'CREDIT' and account = 'GOAL_POOL'",
                item.id()))
        .isEqualTo(1);
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

    return new SuspenseItem(contributionId, goalId, amountPesewas);
  }

  private ResponseEntity<Map<String, Object>> reclassify(
      UUID contributionId, String key, String reason, String token) {
    return post(contributionId, "reclassify", key, reason, token);
  }

  private ResponseEntity<Map<String, Object>> refund(
      UUID contributionId, String key, String reason, String token) {
    return post(contributionId, "refund", key, reason, token);
  }

  private ResponseEntity<Map<String, Object>> post(
      UUID contributionId, String action, String key, String reason, String token) {
    HttpHeaders headers = jsonHeaders(token);
    headers.set("Idempotency-Key", key);
    return rest.exchange(
        "/operator/reconciliation/{id}/" + action,
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

  private long goalRaised(UUID goalId) {
    Long raised =
        jdbc.queryForObject(
            "select coalesce(sum(amount_minor_units), 0) from ledger_entries where account = 'GOAL_POOL' and direction = 'CREDIT' and contribution_id in (select id from contributions where goal_id = ?)",
            Long.class,
            goalId);
    return raised == null ? 0 : raised;
  }

  private long reservedForGoal(UUID goalId) {
    Long reserved =
        jdbc.queryForObject(
            """
            select coalesce(sum(amount_minor_units), 0) from refunds
            where goal_id = ? and state in ('PENDING', 'PROVIDER_PENDING')
              and source_account = 'GOAL_POOL'
            """,
            Long.class,
            goalId);
    return reserved == null ? 0 : reserved;
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
