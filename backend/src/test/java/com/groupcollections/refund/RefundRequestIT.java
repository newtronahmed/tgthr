package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.contribution.ContributionRepository;
import com.groupcollections.identity.OrganizerUser;
import com.groupcollections.operator.Base32;
import com.groupcollections.operator.OperatorRole;
import com.groupcollections.operator.OperatorSecretCipher;
import com.groupcollections.operator.OperatorSessionService;
import com.groupcollections.operator.PasswordHasher;
import com.groupcollections.operator.Totp;
import com.groupcollections.payout.PayoutException;
import com.groupcollections.payout.PayoutFeeProvider;
import com.groupcollections.payout.PayoutRepository;
import com.groupcollections.payout.PayoutService;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakePayoutFeeProvider;
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

@TestPropertySource(
    properties = {
      "collections.operator.enabled=true",
      "collections.operator.totp-secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "collections.operator.session-ttl=PT1H",
      "collections.refunds.enabled=true",
      "collections.refunds.provider=paystack",
      "collections.refunds.partner-retains-processing-fee=true",
      "collections.payout.enabled=true"
    })
@Import(RefundRequestIT.PayoutConfiguration.class)
class RefundRequestIT extends ApiIntegrationTest {

  @TestConfiguration
  static class PayoutConfiguration {
    @Bean
    @Primary
    PayoutFeeProvider fakePayoutFeeProvider() {
      return new FakePayoutFeeProvider();
    }
  }

  private static final String OPERATOR_PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;
  @Autowired private PayoutRepository payouts;
  @Autowired private ContributionRepository contributions;
  @Autowired private PayoutService payoutService;

  @Test
  @Criterion("AC-REFUND-001")
  void requestReservesTheGoalAmountAndWaitsForTheProvider() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.request", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-key-one", "duplicate charge", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));
    Map<String, Object> row = refundRow(refundId);
    assertThat(row.get("state")).isEqualTo("PROVIDER_PENDING");
    assertThat(((Number) row.get("amount_minor_units")).longValue())
        .isEqualTo(contribution.amount());
    assertThat(((Number) row.get("total_minor_units")).longValue()).isEqualTo(contribution.total());
    assertThat(row.get("provider_transaction_reference")).isEqualTo(contribution.id().toString());
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isEqualTo(contribution.amount());
    assertThat(count("select count(*) from ledger_entries where refund_id = ?", refundId)).isZero();
    assertThat(PAYSTACK.refundBodies()).hasSize(1);
    assertThat(PAYSTACK.refundBodies().getFirst())
        .contains(contribution.id().toString(), String.valueOf(contribution.total()));
  }

  @Test
  @Criterion("AC-REFUND-006")
  void sameKeyAndReasonReplaysButADifferentReasonConflicts() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.idempotent", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> first =
        request(contribution.id(), "refund-key-two", "duplicate charge", token);
    ResponseEntity<Map<String, Object>> replay =
        request(contribution.id(), "refund-key-two", "duplicate charge", token);
    ResponseEntity<Map<String, Object>> conflict =
        request(contribution.id(), "refund-key-two", "different reason", token);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody()).containsEntry("code", "IDEMPOTENCY_CONFLICT");
    assertThat(PAYSTACK.refundBodies()).hasSize(1);
  }

  @Test
  @Criterion("AC-REFUND-005")
  void inFlightAndCompletedPayoutsBlockWhileFailedAndCancelledAllow() {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.payout", OperatorRole.REFUND_OPERATOR));
    UUID payoutId = insertPayout(contribution.goalId(), "REQUESTED");

    ResponseEntity<Map<String, Object>> inFlight =
        request(contribution.id(), "refund-key-three", "duplicate charge", token);
    assertThat(inFlight.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(inFlight.getBody()).containsEntry("code", "PAYOUT_IN_FLIGHT");
    assertThat(inFlight.getBody().get("detail").toString()).contains(payoutId.toString());

    jdbc.update("update payouts set state = 'COMPLETED' where id = ?", payoutId);
    ResponseEntity<Map<String, Object>> completed =
        request(contribution.id(), "refund-key-four", "duplicate charge", token);
    assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(completed.getBody()).containsEntry("code", "REFUND_AFTER_PAYOUT");

    jdbc.update("update payouts set state = 'FAILED' where id = ?", payoutId);
    ResponseEntity<Map<String, Object>> allowed =
        request(contribution.id(), "refund-key-five", "duplicate charge", token);
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void aSuspenseContributionMustUseReconciliation() {
    String slug = insertGoal("refund-suspense", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    String contributionId = (String) created.get("id");
    UUID goalId =
        jdbc.queryForObject(
            "select goal_id from contributions where id = ?",
            UUID.class,
            UUID.fromString(contributionId));
    jdbc.update("update goals set status = 'CLOSED' where id = ?", goalId);
    long total = ((Number) created.get("totalPesewas")).longValue();
    PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total);
    String token = signIn(operator("refund.suspense", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        request(UUID.fromString(contributionId), "refund-key-six", "late payment", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry("code", "SUSPENSE_REQUIRES_RECONCILIATION");
  }

  @Test
  @Criterion("AC-OPS-005")
  void aNonRefundOperatorIsForbidden() {
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.reconciler", OperatorRole.RECONCILER));

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-key-seven", "duplicate charge", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).containsEntry("code", "FORBIDDEN");
  }

  @Test
  @Criterion({"AC-REFUND-002", "AC-REFUND-007"})
  void successPostsTheReversalAndRetainedFeeLoss() {
    PAYSTACK.refundScenario("SUCCESS");
    Contribution contribution = confirmedContribution();
    OrganizerUser organizer = settleAndOwn(contribution);
    String token = signIn(operator("refund.success", OperatorRole.REFUND_OPERATOR));
    assertThat(payouts.goalPoolBalance(contribution.goalId()).minorUnits())
        .isEqualTo(contribution.amount());
    assertThat(contributions.goalRaised(contribution.goalId(), "GHS").minorUnits())
        .isEqualTo(contribution.amount());

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-key-eight", "duplicate charge", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));
    assertThat(refundRow(refundId).get("state")).isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, contribution.id()))
        .isEqualTo("REFUNDED");
    assertThat(payouts.goalPoolBalance(contribution.goalId()).minorUnits()).isZero();
    assertThat(contributions.goalRaised(contribution.goalId(), "GHS").minorUnits()).isZero();
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and entry_type = 'REVERSAL'",
                refundId))
        .isEqualTo(4);
    assertThat(
            count(
                "select count(*) from ledger_entries where refund_id = ? and entry_type = 'LOSS_ALLOCATION'",
                refundId))
        .isEqualTo(2);
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isZero();
    assertThat(PAYSTACK.refundBodies()).hasSize(1);

    assertThatThrownBy(
            () ->
                payoutService.request(
                    organizer, contribution.goalId(), "payout-after-refund", contribution.amount()))
        .isInstanceOfSatisfying(
            PayoutException.class,
            failure -> assertThat(failure.code()).isEqualTo("INSUFFICIENT_SETTLED_BALANCE"));
  }

  @Test
  @Criterion("AC-REFUND-003")
  void providerFailureReleasesTheReservationWithoutPosting() {
    PAYSTACK.refundScenario("FAILED");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.failed", OperatorRole.REFUND_OPERATOR));

    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-key-nine", "duplicate charge", token);

    UUID refundId = UUID.fromString((String) response.getBody().get("id"));
    assertThat(refundRow(refundId).get("state")).isEqualTo("FAILED");
    assertThat(count("select count(*) from ledger_entries where refund_id = ?", refundId)).isZero();
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isZero();
  }

  @Test
  @Criterion("AC-REFUND-004")
  void unknownResultStaysPendingAndVerifyCompletes() {
    PAYSTACK.refundScenario("SERVER_ERROR");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.verify", OperatorRole.REFUND_OPERATOR));
    ResponseEntity<Map<String, Object>> response =
        request(contribution.id(), "refund-key-ten", "duplicate charge", token);
    UUID refundId = UUID.fromString((String) response.getBody().get("id"));
    assertThat(refundRow(refundId).get("state")).isEqualTo("PROVIDER_PENDING");
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isEqualTo(contribution.amount());

    PAYSTACK.refundScenario("SUCCESS");
    ResponseEntity<Map<String, Object>> verified = verify(refundId, "provider verification", token);

    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refundRow(refundId).get("state")).isEqualTo("SUCCEEDED");
    assertThat(payouts.reservedMinorUnits(contribution.goalId())).isZero();
  }

  @Test
  @Criterion("AC-REFUND-006")
  void concurrentRequestsHaveOneWinner() throws Exception {
    PAYSTACK.refundScenario("PENDING");
    Contribution contribution = confirmedContribution();
    String token = signIn(operator("refund.race", OperatorRole.REFUND_OPERATOR));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first =
          executor.submit(
              () -> {
                start.await();
                return request(contribution.id(), "refund-race-one", "race one", token)
                    .getStatusCode()
                    .value();
              });
      Future<Integer> second =
          executor.submit(
              () -> {
                start.await();
                return request(contribution.id(), "refund-race-two", "race two", token)
                    .getStatusCode()
                    .value();
              });
      start.countDown();
      List<Integer> statuses =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("select count(*) from refunds where contribution_id = ?", contribution.id()))
        .isEqualTo(1);
  }

  private Contribution confirmedContribution() {
    String slug = insertGoal("refund-core", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 10_000, "0240000001").getBody();
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

  private OrganizerUser settleAndOwn(Contribution contribution) {
    UUID userId =
        jdbc.queryForObject(
            """
            insert into users (phone_ciphertext, phone_hash, display_name)
            values (?, ?, 'Test Organizer')
            returning id
            """,
            UUID.class,
            randomBytes(),
            randomBytes());
    jdbc.update(
        """
        update goals
        set created_by_user_id = ?,
            destination_phone_ciphertext = ?,
            destination_phone_hash = ?,
            destination_network = 'MTN',
            destination_account_name = 'Test Organizer',
            destination_locked_at = now(),
            create_idempotency_key = ?,
            hold_window_minutes = 1
        where id = ?
        """,
        userId,
        randomBytes(),
        randomBytes(),
        "goal-" + UUID.randomUUID(),
        contribution.goalId());
    UUID settlementId =
        jdbc.queryForObject(
            """
            insert into settlements
                (provider, provider_settlement_reference, settlement_date,
                 gross_amount_minor_units, currency_code, fees_minor_units,
                 net_amount_minor_units, direction, status, received_at)
            values ('PAYSTACK', ?, current_date, ?, 'GHS', 0, ?, 'CREDIT', 'SETTLED', now())
            returning id
            """,
            UUID.class,
            "settle-" + UUID.randomUUID(),
            contribution.total(),
            contribution.total());
    jdbc.update(
        """
        insert into settlement_items
            (settlement_id, contribution_id, item_type, direction, gross_minor_units,
             currency_code, fee_minor_units, net_minor_units, goal_amount_minor_units)
        values (?, ?, 'CONTRIBUTION', 'CREDIT', ?, 'GHS', 0, ?, ?)
        """,
        settlementId,
        contribution.id(),
        contribution.total(),
        contribution.total(),
        contribution.amount());
    return new OrganizerUser(userId, "Test Organizer", "Test Organizer", Instant.now());
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

  private ResponseEntity<Map<String, Object>> verify(UUID refundId, String reason, String token) {
    return rest.exchange(
        "/operator/refunds/{id}/verify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", reason), jsonHeaders(token)),
        new ParameterizedTypeReference<>() {},
        refundId);
  }

  private Map<String, Object> refundRow(UUID refundId) {
    return jdbc.queryForMap("select * from refunds where id = ?", refundId);
  }

  private UUID insertPayout(UUID goalId, String state) {
    UUID userId =
        jdbc.queryForObject(
            """
            insert into users (phone_ciphertext, phone_hash, display_name)
            values (?, ?, 'Test operator')
            returning id
            """,
            UUID.class,
            new byte[32],
            randomBytes());
    return jdbc.queryForObject(
        """
        insert into payouts
            (goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             state, idempotency_key, created_at, updated_at)
        values (?, ?, 100, 1, 99, ?, ?, now(), now())
        returning id
        """,
        UUID.class,
        goalId,
        userId,
        state,
        "payout-" + UUID.randomUUID());
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static HttpHeaders jsonHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.add(HttpHeaders.COOKIE, OperatorSessionService.COOKIE_NAME + "=" + token);
    }
    return headers;
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private record TestOperator(UUID id, String username, String secretBase32) {}

  private record Contribution(UUID id, UUID goalId, long amount, long total) {}
}
