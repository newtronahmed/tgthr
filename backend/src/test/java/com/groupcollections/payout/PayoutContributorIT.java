package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.alerts.Alert;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.security.PhoneCipher;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakeSmsSender;
import com.groupcollections.support.RecordingAlertPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

@Import(PayoutContributorIT.ContributorTestConfiguration.class)
@TestPropertySource(properties = "collections.payout.enabled=true")
class PayoutContributorIT extends ApiIntegrationTest {

  @TestConfiguration
  static class ContributorTestConfiguration {
    @Bean
    @Primary
    SmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }

    @Bean
    @Primary
    PayoutFeeProvider fakePayoutFeeProvider() {
      return new FakePayoutFeeProvider();
    }
  }

  @Autowired PayoutNotificationService notifications;
  @Autowired PhoneCipher phones;

  @BeforeEach
  void resetFakes() {
    FakeSmsSender.reset();
    RecordingAlertPublisher.reset();
  }

  @Test
  @Criterion("AC-PAYOUT-025")
  void publicGoalShowsHeldAndCompletedPayoutsAndStoresReports() {
    String slug = insertGoal("public-payout", "OPEN", null);
    UUID goalId = jdbc.queryForObject("select id from goals where slug = ?", UUID.class, slug);
    UUID requester = insertUser("0240000501");
    String approverPhone = "0240000506";
    jdbc.update(
        """
        update goals set second_approver_name = 'Esi Mensah',
            second_approver_phone_ciphertext = ?, second_approver_phone_hash = ?,
            second_approver_set_at = now()
        where id = ?
        """,
        phones.encrypt(approverPhone),
        phones.hash(approverPhone),
        goalId);
    insertContribution(goalId, "0240000507", 1_000, "CONFIRMED", false);
    UUID held = insertPayout(goalId, requester, "HOLD", 1_000, true, false);
    UUID completed = insertPayout(goalId, requester, "COMPLETED", 2_000, false, true);
    UUID requested = insertPayout(goalId, requester, "REQUESTED", 3_000, false, false);
    String otherSlug = insertGoal("other-payout", "OPEN", null);
    UUID otherGoal =
        jdbc.queryForObject("select id from goals where slug = ?", UUID.class, otherSlug);
    UUID otherPayout = insertPayout(otherGoal, requester, "HOLD", 9_000, true, false);
    UUID otherContribution = insertContribution(otherGoal, "0240000510", 1_000, "CONFIRMED", false);

    ResponseEntity<Map<String, Object>> publicGoal =
        rest.exchange(
            "/public/goals/{slug}",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            new ParameterizedTypeReference<>() {},
            slug);
    assertThat(publicGoal.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(publicGoal.getBody()).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> payouts =
        (List<Map<String, Object>>) publicGoal.getBody().get("payouts");
    assertThat(payouts).hasSize(2);
    assertThat(payouts).extracting(item -> item.get("state")).containsExactly("HOLD", "COMPLETED");
    assertThat(((Number) payouts.get(0).get("amountPesewas")).longValue()).isEqualTo(1_000L);
    assertThat(((Number) payouts.get(0).get("feePesewas")).longValue()).isZero();
    assertThat(payouts.get(0).get("holdExpiresAt")).isNotNull();
    assertThat(((Number) payouts.get(1).get("amountPesewas")).longValue()).isEqualTo(2_000L);
    assertThat(payouts.get(1).get("completedAt")).isNotNull();
    assertThat(payouts).noneMatch(item -> item.get("id").equals(otherPayout.toString()));
    assertThat(payouts.stream().map(Map::toString))
        .allMatch(
            text ->
                !text.contains("0240000501")
                    && !text.contains(approverPhone)
                    && !text.contains("0240000507"));
    ResponseEntity<String> raw =
        rest.exchange(
            "/public/goals/{slug}",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            String.class,
            slug);
    assertThat(raw.getBody()).doesNotContain("0240000501");
    assertThat(raw.getBody()).doesNotContain(approverPhone);
    assertThat(raw.getBody()).doesNotContain("0240000507");
    assertThat(raw.getBody()).doesNotContain("payout_phone");

    ResponseEntity<Void> report =
        rest.exchange(
            "/public/goals/{slug}/payouts/{payoutId}/report",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "The amount looks wrong"), jsonHeaders()),
            Void.class,
            slug,
            held);
    assertThat(report.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_reports where payout_id = ?", Integer.class, held))
        .isEqualTo(1);
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.PAYOUT_REPORTED);
    assertThat(jdbc.queryForObject("select state from payouts where id = ?", String.class, held))
        .isEqualTo("HOLD");

    assertThat(completed).isNotNull();

    ResponseEntity<Void> hiddenReport =
        rest.exchange(
            "/public/goals/{slug}/payouts/{payoutId}/report",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Hidden payout"), jsonHeaders()),
            Void.class,
            slug,
            requested);
    assertThat(hiddenReport.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_reports where payout_id = ?",
                Integer.class,
                requested))
        .isZero();

    ResponseEntity<Void> otherGoalReport =
        rest.exchange(
            "/public/goals/{slug}/payouts/{payoutId}/report",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Other goal"), jsonHeaders()),
            Void.class,
            slug,
            otherPayout);
    assertThat(otherGoalReport.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_reports where payout_id = ?",
                Integer.class,
                otherPayout))
        .isZero();

    ResponseEntity<Void> wrongContribution =
        rest.exchange(
            "/public/goals/{slug}/payouts/{payoutId}/report",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("reason", "Wrong contribution", "contributionId", otherContribution),
                jsonHeaders()),
            Void.class,
            slug,
            held);
    assertThat(wrongContribution.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_reports where payout_id = ?", Integer.class, held))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-PAYOUT-026")
  void completedPayoutNotifiesEachContributorOnce() {
    String slug = insertGoal("notify-payout", "OPEN", null);
    UUID goalId = jdbc.queryForObject("select id from goals where slug = ?", UUID.class, slug);
    UUID requester = insertUser("0240000502");
    String payerOne = "0240000503";
    String payerTwo = "0240000504";
    insertContribution(goalId, payerOne, 1_000, "CONFIRMED", false);
    insertContribution(goalId, payerOne, 1_000, "CONFIRMED", false);
    insertContribution(goalId, payerTwo, 1_000, "CONFIRMED", false);
    insertContribution(goalId, payerOne, 1_000, "PENDING", false);
    insertContribution(goalId, payerOne, 1_000, "CONFIRMED", true);
    UUID payout = insertPayout(goalId, requester, "COMPLETED", 3_000, false, true);

    assertThat(notifications.notifyCompleted(payout)).isEqualTo(2);
    assertThat(FakeSmsSender.sent()).hasSize(2);
    assertThat(FakeSmsSender.sent().stream().map(FakeSmsSender.Sent::phone))
        .containsExactlyInAnyOrder("+233240000503", "+233240000504");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_notifications where payout_id = ?",
                Integer.class,
                payout))
        .isEqualTo(2);
    assertThat(notifications.notifyCompleted(payout)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_notifications where payout_id = ?",
                Integer.class,
                payout))
        .isEqualTo(2);
  }

  @Test
  @Criterion("AC-PAYOUT-026")
  void notificationFailureAlertsWithoutChangingThePayout() {
    String slug = insertGoal("notify-failure", "OPEN", null);
    UUID goalId = jdbc.queryForObject("select id from goals where slug = ?", UUID.class, slug);
    UUID requester = insertUser("0240000508");
    insertContribution(goalId, "0240000509", 1_000, "CONFIRMED", false);
    UUID payout = insertPayout(goalId, requester, "COMPLETED", 1_000, false, true);
    FakeSmsSender.failNext(true);

    assertThat(notifications.notifyCompleted(payout)).isZero();
    assertThat(jdbc.queryForObject("select state from payouts where id = ?", String.class, payout))
        .isEqualTo("COMPLETED");
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.SMS_FAILURE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_notifications where payout_id = ?",
                Integer.class,
                payout))
        .isZero();
    assertThat(notifications.notifyCompleted(payout)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_notifications where payout_id = ?",
                Integer.class,
                payout))
        .isEqualTo(1);
  }

  private UUID insertUser(String phone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into users (id, phone_ciphertext, phone_hash) values (?, ?, ?)",
        id,
        phones.encrypt(phone),
        phones.hash(phone));
    return id;
  }

  private UUID insertPayout(
      UUID goalId, UUID requester, String state, long amount, boolean held, boolean completed) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        """
        insert into payouts
            (id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             state, idempotency_key, hold_expires_at, completed_at, created_at)
        values (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
        """,
        id,
        goalId,
        requester,
        amount,
        amount,
        state,
        "visibility-" + id,
        held ? Timestamp.from(now.plusSeconds(3600)) : null,
        completed ? Timestamp.from(now) : null,
        Timestamp.from(now));
    return id;
  }

  private UUID insertContribution(
      UUID goalId, String payer, long amount, String state, boolean requiresReconciliation) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into contributions
            (id, goal_id, amount_minor_units, processing_fee_minor_units, platform_fee_minor_units,
             total_minor_units, state, provider_reference, payer_phone_ciphertext,
             payer_phone_hash, idempotency_key, confirmed_at, requires_reconciliation)
        values (?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, now(), ?)
        """,
        id,
        goalId,
        amount,
        amount,
        state,
        "notify-" + UUID.randomUUID(),
        phones.encrypt(payer),
        phones.hash(payer),
        "notify-" + UUID.randomUUID(),
        requiresReconciliation);
    return id;
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
