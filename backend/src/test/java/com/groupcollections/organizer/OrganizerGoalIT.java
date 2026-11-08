package com.groupcollections.organizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.alerts.Alert;
import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakeSmsSender;
import com.groupcollections.support.RecordingAlertPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

@Import(OrganizerGoalIT.GoalTestConfiguration.class)
class OrganizerGoalIT extends ApiIntegrationTest {

  @TestConfiguration
  static class GoalTestConfiguration {
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
  }

  @BeforeEach
  void resetFakes() {
    FakeSmsSender.reset();
    FakeAccountResolver.reset();
  }

  @Test
  @Criterion("AC-ORG-005")
  void authenticatedOrganizerCreatesAPublicGoal() {
    Session session = session("0240000101", "Ama Mensah");
    FakeAccountResolver.name("0240000102", "Kofi Mensah");
    int ledgerBefore = count("select count(*) from ledger_entries");
    double createdBefore = counter("goals.created");

    ResponseEntity<Map<String, Object>> response =
        create(session, body("0240000102", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> goal = response.getBody();
    assertThat(goal).isNotNull();
    assertThat(goal.get("status")).isEqualTo("OPEN");
    assertThat(goal.get("destinationAccountName")).isEqualTo("Kofi Mensah");
    assertThat((String) goal.get("slug")).hasSizeGreaterThanOrEqualTo(12);
    assertThat(goal).doesNotContainKeys("phone", "destinationPhone");
    UUID goalId = UUID.fromString((String) goal.get("id"));
    assertThat(
            count(
                "select count(*) from goals where id = ? and created_by_user_id = ?",
                goalId,
                session.userId()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from goal_events where goal_id = ? and event = 'CREATED'", goalId))
        .isEqualTo(1);
    assertThat(count("select count(*) from contributions where goal_id = ?", goalId)).isZero();
    assertThat(count("select count(*) from ledger_entries")).isEqualTo(ledgerBefore);
    assertThat(counter("goals.created")).isGreaterThan(createdBefore);

    ResponseEntity<Map<String, Object>> publicGoal =
        rest.exchange(
            "/public/goals/{slug}",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            new ParameterizedTypeReference<>() {},
            goal.get("slug"));
    assertThat(publicGoal.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(publicGoal.getBody()).isNotNull();
    assertThat(publicGoal.getBody().get("status")).isEqualTo("OPEN");
    @SuppressWarnings("unchecked")
    Map<String, Object> payoutRules = (Map<String, Object>) publicGoal.getBody().get("payoutRules");
    assertThat(((Number) payoutRules.get("secondApproverThresholdPesewas")).longValue())
        .isEqualTo(50_000L);
  }

  @Test
  @Criterion("AC-ORG-009")
  void organizerListShowsRaisedTotalsFromTheLedger() {
    Session session = session("0240000140", "Ama Mensah");
    ResponseEntity<Map<String, Object>> created =
        create(session, body("0240000141", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    UUID goalId = UUID.fromString((String) created.getBody().get("id"));
    UUID contributionId = UUID.randomUUID();
    String reference = "confirm-" + UUID.randomUUID();
    jdbc.update(
        """
        insert into contributions
            (id, goal_id, amount_pesewas, processing_fee_pesewas, platform_fee_pesewas,
             total_pesewas, state, provider_reference, payer_phone_ciphertext,
             payer_phone_hash, idempotency_key, confirmed_at)
        values (?, ?, 5000, 0, 0, 5000, 'CONFIRMED', ?, ?, ?, ?, now())
        """,
        contributionId,
        goalId,
        reference,
        "cipher".getBytes(StandardCharsets.UTF_8),
        new byte[32],
        "ledger-" + reference);
    UUID posting = UUID.randomUUID();
    jdbc.update(
        """
        insert into ledger_entries
            (posting_set_id, contribution_id, account, entry_type, direction, amount_pesewas)
        values (?, ?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', 5000),
               (?, ?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', 5000)
        """,
        posting,
        contributionId,
        posting,
        contributionId);
    UUID reversal = UUID.randomUUID();
    jdbc.update(
        """
        insert into ledger_entries
            (posting_set_id, contribution_id, account, entry_type, direction, amount_pesewas)
        values (?, ?, 'GOAL_POOL', 'REVERSAL', 'DEBIT', 1000),
               (?, ?, 'PARTNER_HELD', 'REVERSAL', 'CREDIT', 1000)
        """,
        reversal,
        contributionId,
        reversal,
        contributionId);

    Map<String, Object> listed = getGoal(session, goalId.toString()).getBody();
    assertThat(listed).isNotNull();
    assertThat(((Number) listed.get("raisedPesewas")).longValue()).isEqualTo(4_000L);
    assertThat(((Number) listed.get("contributorCount")).longValue()).isEqualTo(1L);
  }

  @Test
  @Criterion("AC-ORG-006")
  void invalidTargetFieldsAreRejected() {
    Session session = session("0240000103", "Ama Mensah");

    Map<String, Object> fixedWithoutAmount =
        body("0240000104", "BENEFICIARY_MOMO", "FIXED", null, null);
    ResponseEntity<Map<String, Object>> fixed = create(session, fixedWithoutAmount, uniqueKey());
    assertThat(fixed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
        .isZero();

    Map<String, Object> openTooLow = body("0240000104", "BENEFICIARY_MOMO", "OPEN", null, 999L);
    ResponseEntity<Map<String, Object>> low = create(session, openTooLow, uniqueKey());
    assertThat(low.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
        .isZero();

    Map<String, Object> openAtFloor = body("0240000104", "BENEFICIARY_MOMO", "OPEN", null, 1_000L);
    ResponseEntity<Map<String, Object>> accepted = create(session, openAtFloor, uniqueKey());
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-ORG-007")
  void unresolvableOrMismatchedDestinationsAreRejected() {
    Session admin = session("0240000105", "Ama Mensah");
    FakeAccountResolver.name("0240000105", "Ama Mensah");
    ResponseEntity<Map<String, Object>> matched =
        create(admin, body("0240000105", "ADMIN_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(matched.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    Session mismatch = session("0240000107", "Ama Mensah");
    FakeAccountResolver.name("0240000107", "Kofi Mensah");
    double failedBefore = counter("destination.resolve.failed", "reason", "name_mismatch");
    ResponseEntity<Map<String, Object>> rejected =
        create(mismatch, body("0240000107", "ADMIN_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(rejected.getBody()).isNotNull();
    assertThat(rejected.getBody().get("code")).isEqualTo("DESTINATION_UNVERIFIED");
    assertThat(counter("destination.resolve.failed", "reason", "name_mismatch"))
        .isGreaterThan(failedBefore);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.DESTINATION_UNVERIFIED);
    assertThat(count("select count(*) from goals where created_by_user_id = ?", mismatch.userId()))
        .isZero();

    Session notOwner = session("0240000130", "Ama Mensah");
    FakeAccountResolver.name("0240000131", "Ama Mensah");
    ResponseEntity<Map<String, Object>> foreignAccount =
        create(notOwner, body("0240000131", "ADMIN_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(foreignAccount.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(foreignAccount.getBody()).isNotNull();
    assertThat(foreignAccount.getBody().get("code")).isEqualTo("DESTINATION_UNVERIFIED");

    Session noLegalName = session("0240000132", null);
    FakeAccountResolver.name("0240000132", "Ama");
    ResponseEntity<Map<String, Object>> legal =
        create(noLegalName, body("0240000132", "ADMIN_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(legal.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(legal.getBody()).isNotNull();
    assertThat(legal.getBody().get("code")).isEqualTo("LEGAL_NAME_REQUIRED");

    Session unresolved = session("0240000133", "Ama Mensah");
    FakeAccountResolver.unresolvable(true);
    ResponseEntity<Map<String, Object>> missing =
        create(
            unresolved, body("0240000134", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(missing.getBody()).isNotNull();
    assertThat(missing.getBody().get("code")).isEqualTo("DESTINATION_UNVERIFIED");

    Session outage = session("0240000109", null);
    FakeAccountResolver.unresolvable(false);
    FakeAccountResolver.outage(true);
    ResponseEntity<Map<String, Object>> unavailable =
        create(outage, body("0240000110", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(unavailable.getBody()).isNotNull();
    assertThat(unavailable.getBody().get("code")).isEqualTo("DESTINATION_CHECK_UNAVAILABLE");
    assertThat(count("select count(*) from goals where created_by_user_id = ?", outage.userId()))
        .isZero();
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.DESTINATION_UNAVAILABLE);
  }

  @Test
  @Criterion("AC-ORG-008")
  void retriedGoalCreationReturnsTheOriginal() {
    Session session = session("0240000111", "Ama Mensah");
    Map<String, Object> body = body("0240000112", "BENEFICIARY_MOMO", "FIXED", 5_000L, null);
    String key = uniqueKey();

    assertThat(createRaw(session, body, null).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(createRaw(session, body, "short").getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(createRaw(session, body, "        ").getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
        .isZero();

    ResponseEntity<Map<String, Object>> first = create(session, body, key);
    ResponseEntity<Map<String, Object>> retry = create(session, body, key);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(retry.getBody()).isNotNull();
    assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
        .isEqualTo(1);

    ResponseEntity<Map<String, Object>> other = create(session, body, uniqueKey());
    assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(other.getBody().get("id")).isNotEqualTo(first.getBody().get("id"));
    assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
        .isEqualTo(2);
  }

  @Test
  @Criterion("AC-ORG-009")
  void organizersSeeOnlyTheirOwnGoals() {
    Session owner = session("0240000113", "Ama Mensah");
    ResponseEntity<Map<String, Object>> created =
        create(owner, body("0240000114", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String goalId = (String) created.getBody().get("id");

    Session other = session("0240000115", "Kofi Mensah");
    ResponseEntity<List<Map<String, Object>>> otherList =
        rest.exchange(
            "/organizer/goals",
            HttpMethod.GET,
            new HttpEntity<>(cookie(other)),
            new ParameterizedTypeReference<>() {});
    assertThat(otherList.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(otherList.getBody()).isNotNull();
    assertThat(otherList.getBody()).noneMatch(goal -> goalId.equals(goal.get("id")));
    assertThat(getGoal(other, goalId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ResponseEntity<List<Map<String, Object>>> ownerList =
        rest.exchange(
            "/organizer/goals",
            HttpMethod.GET,
            new HttpEntity<>(cookie(owner)),
            new ParameterizedTypeReference<>() {});
    assertThat(ownerList.getBody()).anyMatch(goal -> goalId.equals(goal.get("id")));
  }

  @Test
  @Criterion("AC-ORG-012")
  void organizerResponsesNeverContainPhoneNumbers() {
    Session session = session("0240000116", "Ama Mensah");
    FakeAccountResolver.name("0240000117", "Kofi Mensah");
    String key = uniqueKey();

    ResponseEntity<String> rawCreate =
        rest.exchange(
            "/organizer/goals",
            HttpMethod.POST,
            json(session, key, body("0240000117", "BENEFICIARY_MOMO", "FIXED", 5_000L, null)),
            String.class);
    assertThat(rawCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(rawCreate.getBody())
        .doesNotContain("0240000116")
        .doesNotContain("0240000117")
        .doesNotContain("destinationPhone");

    ResponseEntity<String> rawList =
        rest.exchange(
            "/organizer/goals", HttpMethod.GET, new HttpEntity<>(cookie(session)), String.class);
    assertThat(rawList.getBody()).doesNotContain("0240000117");

    Map<String, Object> stored =
        jdbc.queryForMap(
            """
            select destination_phone_ciphertext, destination_phone_hash
            from goals where created_by_user_id = ? order by created_at desc limit 1
            """,
            session.userId());
    assertThat(stored.get("destination_phone_ciphertext")).isNotNull();
    assertThat(stored.get("destination_phone_hash")).isNotNull();
    assertThat((byte[]) stored.get("destination_phone_ciphertext"))
        .isNotEqualTo("0240000117".getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) stored.get("destination_phone_hash")).hasSize(32);

    Map<String, Object> organizer =
        jdbc.queryForMap(
            "select phone_ciphertext, phone_hash from users where id = ?", session.userId());
    assertThat((byte[]) organizer.get("phone_ciphertext"))
        .isNotEqualTo("0240000116".getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) organizer.get("phone_hash")).hasSize(32);

    String slug =
        jdbc.queryForObject(
            "select slug from goals where created_by_user_id = ? order by created_at desc limit 1",
            String.class,
            session.userId());
    ResponseEntity<String> rawPublic =
        rest.exchange(
            "/public/goals/{slug}",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            String.class,
            slug);
    assertThat(rawPublic.getBody()).doesNotContain("0240000116").doesNotContain("0240000117");
    ResponseEntity<String> rawGoal =
        rest.exchange(
            "/organizer/goals/{id}",
            HttpMethod.GET,
            new HttpEntity<>(cookie(session)),
            String.class,
            jdbc.queryForObject("select id from goals where slug = ?", String.class, slug));
    assertThat(rawGoal.getBody()).doesNotContain("0240000116").doesNotContain("0240000117");
    ResponseEntity<String> rawClose =
        rest.exchange(
            "/organizer/goals/{id}/close",
            HttpMethod.POST,
            new HttpEntity<>(cookie(session)),
            String.class,
            jdbc.queryForObject("select id from goals where slug = ?", String.class, slug));
    assertThat(rawClose.getBody()).doesNotContain("0240000116").doesNotContain("0240000117");
  }

  @Test
  @Criterion("AC-ORG-013")
  void ownersCloseGoalsAndContributionsStop() {
    Session session = session("0240000118", "Ama Mensah");
    ResponseEntity<Map<String, Object>> created =
        create(session, body("0240000119", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    String goalId = (String) created.getBody().get("id");
    String slug = (String) created.getBody().get("slug");

    ResponseEntity<Map<String, Object>> closed = close(session, goalId);
    assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(closed.getBody().get("status")).isEqualTo("CLOSED");
    assertThat(close(session, goalId).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            count(
                "select count(*) from goal_events where goal_id = ? and event = 'CLOSED'",
                UUID.fromString(goalId)))
        .isEqualTo(1);
    assertThat(createContribution(slug, 5_000, "0240000120").getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    Session other = session("0240000121", "Kofi Mensah");
    assertThat(close(other, goalId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    Session frozenSession = session("0240000135", "Ama Mensah");
    ResponseEntity<Map<String, Object>> frozenGoal =
        create(
            frozenSession,
            body("0240000136", "BENEFICIARY_MOMO", "FIXED", 5_000L, null),
            uniqueKey());
    UUID frozenId = UUID.fromString((String) frozenGoal.getBody().get("id"));
    jdbc.update("update goals set status = 'FROZEN' where id = ?", frozenId);
    ResponseEntity<Map<String, Object>> frozenClose = close(frozenSession, frozenId.toString());
    assertThat(frozenClose.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(frozenClose.getBody()).isNotNull();
    assertThat(frozenClose.getBody().get("code")).isEqualTo("GOAL_NOT_CLOSABLE");
    assertThat(
            count(
                "select count(*) from goal_events where goal_id = ? and event = 'CLOSED'",
                frozenId))
        .isZero();
  }

  @Test
  void concurrentSameKeyCreatesOneGoal() throws Exception {
    Session session = session("0240000170", "Ama Mensah");
    Map<String, Object> body = body("0240000171", "BENEFICIARY_MOMO", "FIXED", 5_000L, null);
    String key = uniqueKey();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<ResponseEntity<Map<String, Object>>> first =
          pool.submit(() -> createAfter(start, session, body, key));
      Future<ResponseEntity<Map<String, Object>>> second =
          pool.submit(() -> createAfter(start, session, body, key));
      start.countDown();
      ResponseEntity<Map<String, Object>> one = first.get(10, TimeUnit.SECONDS);
      ResponseEntity<Map<String, Object>> two = second.get(10, TimeUnit.SECONDS);
      assertThat(one.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(two.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(one.getBody().get("id")).isEqualTo(two.getBody().get("id"));
      assertThat(count("select count(*) from goals where created_by_user_id = ?", session.userId()))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void payoutAcceptsE164Destination() {
    Session session = session("0240000160", "Ama Mensah");
    ResponseEntity<Map<String, Object>> local =
        create(session, body("0240000161", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    ResponseEntity<Map<String, Object>> e164 =
        create(
            session, body("+233240000161", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    assertThat(local.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(e164.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    byte[] localHash =
        jdbc.queryForObject(
            "select destination_phone_hash from goals where id = ?",
            byte[].class,
            UUID.fromString((String) local.getBody().get("id")));
    byte[] e164Hash =
        jdbc.queryForObject(
            "select destination_phone_hash from goals where id = ?",
            byte[].class,
            UUID.fromString((String) e164.getBody().get("id")));
    assertThat(e164Hash).isEqualTo(localHash);
  }

  @Test
  void destinationCannotBeChangedAfterLocking() {
    Session session = session("0240000150", "Ama Mensah");
    ResponseEntity<Map<String, Object>> created =
        create(session, body("0240000151", "BENEFICIARY_MOMO", "FIXED", 5_000L, null), uniqueKey());
    UUID goalId = UUID.fromString((String) created.getBody().get("id"));

    for (String sql :
        List.of(
            "update goals set destination_phone_hash = ? where id = ?",
            "update goals set destination_account_name = 'Other' where id = ?",
            "update goals set destination_network = 'TELECEL' where id = ?",
            "update goals set destination_type = 'ADMIN_MOMO' where id = ?",
            "update goals set destination_locked_at = now() where id = ?")) {
      if (sql.contains("phone_hash")) {
        assertThatThrownBy(() -> jdbc.update(sql, new byte[32], goalId))
            .hasMessageContaining("goal destination is locked");
      } else {
        assertThatThrownBy(() -> jdbc.update(sql, goalId))
            .hasMessageContaining("goal destination is locked");
      }
    }
    assertThat(
            jdbc.update(
                "update goals set destination_account_name = destination_account_name where id = ?",
                goalId))
        .isEqualTo(1);
    assertThat(close(session, goalId.toString()).getBody().get("status")).isEqualTo("CLOSED");
  }

  private Session session(String phone, String legalName) {
    requestOtp(phone);
    ResponseEntity<Map<String, Object>> verified =
        verify(phone, FakeSmsSender.latestCode(), legalName);
    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = token(verified);
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

  private ResponseEntity<Map<String, Object>> createAfter(
      CountDownLatch start, Session session, Map<String, Object> body, String key)
      throws Exception {
    start.await();
    return create(session, body, key);
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

  private ResponseEntity<Map<String, Object>> create(
      Session session, Map<String, Object> body, String idempotencyKey) {
    return rest.exchange(
        "/organizer/goals",
        HttpMethod.POST,
        json(session, idempotencyKey, body),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<String> createRaw(
      Session session, Map<String, Object> body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return rest.exchange(
        "/organizer/goals", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<Map<String, Object>> getGoal(Session session, String goalId) {
    return rest.exchange(
        "/organizer/goals/{id}",
        HttpMethod.GET,
        new HttpEntity<>(cookie(session)),
        new ParameterizedTypeReference<>() {},
        goalId);
  }

  private ResponseEntity<Map<String, Object>> close(Session session, String goalId) {
    return rest.exchange(
        "/organizer/goals/{id}/close",
        HttpMethod.POST,
        new HttpEntity<>(cookie(session)),
        new ParameterizedTypeReference<>() {},
        goalId);
  }

  private HttpEntity<Map<String, Object>> json(
      Session session, String idempotencyKey, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return new HttpEntity<>(body, headers);
  }

  private static HttpHeaders cookie(Session session) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return headers;
  }

  private static String token(ResponseEntity<Map<String, Object>> response) {
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).startsWith("tgthr_session=");
    return setCookie.substring("tgthr_session=".length(), setCookie.indexOf(';'));
  }

  private static Map<String, Object> body(
      String destinationPhone,
      String destinationType,
      String targetType,
      Long fixedAmountPesewas,
      Long minimumAmountPesewas) {
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Ama's funeral");
    body.put("template", "FUNERAL");
    body.put("targetType", targetType);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", destinationType);
    body.put("destinationPhone", destinationPhone);
    body.put("network", "MTN");
    body.put("secondApprover", "OFF");
    body.put("holdWindowMinutes", 60);
    body.put("surplusRule", "PAY_BENEFICIARY");
    if (fixedAmountPesewas != null) {
      body.put("fixedAmountPesewas", fixedAmountPesewas);
    }
    if (minimumAmountPesewas != null) {
      body.put("minimumAmountPesewas", minimumAmountPesewas);
    }
    return body;
  }

  private static String uniqueKey() {
    return "goal-" + UUID.randomUUID();
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private record Session(String token, UUID userId) {}
}
