package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakeSmsSender;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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

@Import(GoalPayoutSettingsIT.GoalPayoutTestConfiguration.class)
class GoalPayoutSettingsIT extends ApiIntegrationTest {

  @TestConfiguration
  static class GoalPayoutTestConfiguration {
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
  @Criterion({"AC-PAYOUT-006", "INV-PAYOUT-006"})
  void approverIsRequiredDistinctEncryptedAndOnlyTheNameIsPublic() {
    Session session = session("0240000201", "Ama Mensah");
    String destination = "0240000202";
    FakeAccountResolver.name(destination, "Kofi Mensah");

    Map<String, Object> noApprover = goalBody(destination, "ALWAYS", null, null);
    ResponseEntity<Map<String, Object>> created = create(session, noApprover, uniqueKey());
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isNotNull();
    assertThat(created.getBody()).doesNotContainKey("secondApproverPhone");
    assertThat(created.getBody().get("secondApproverName")).isNull();
    UUID goalId = UUID.fromString((String) created.getBody().get("id"));
    String slug = (String) created.getBody().get("slug");

    ResponseEntity<Map<String, Object>> holdOnly =
        updateSettings(session, goalId, Map.of("holdWindowMinutes", 30));
    assertThat(holdOnly.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(holdOnly.getBody()).isNotNull();
    assertThat(holdOnly.getBody().get("code")).isEqualTo("APPROVER_REQUIRED");

    String approverPhone = "0240000203";
    ResponseEntity<Map<String, Object>> updated =
        updateSettings(
            session,
            goalId,
            Map.of(
                "secondApproverName",
                "Esi Mensah",
                "secondApproverPhone",
                approverPhone,
                "holdWindowMinutes",
                30));
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody()).isNotNull();
    assertThat(updated.getBody().get("secondApproverName")).isEqualTo("Esi Mensah");
    assertThat(updated.getBody()).doesNotContainKey("secondApproverPhone");
    assertThat(updated.getBody().get("holdWindowMinutes")).isEqualTo(30);

    Map<String, Object> stored =
        jdbc.queryForMap(
            """
            select second_approver_name, second_approver_phone_ciphertext,
                   second_approver_phone_hash, second_approver_set_at
            from goals where id = ?
            """,
            goalId);
    assertThat(stored.get("second_approver_name")).isEqualTo("Esi Mensah");
    assertThat((byte[]) stored.get("second_approver_phone_ciphertext"))
        .isNotEqualTo(approverPhone.getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) stored.get("second_approver_phone_hash")).hasSize(32);
    assertThat(stored.get("second_approver_set_at")).isNotNull();

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
    Map<String, Object> rules = (Map<String, Object>) publicGoal.getBody().get("payoutRules");
    assertThat(rules.get("secondApproverName")).isEqualTo("Esi Mensah");
    ResponseEntity<String> rawPublic =
        rest.exchange(
            "/public/goals/{slug}",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            String.class,
            slug);
    assertThat(rawPublic.getBody()).doesNotContain(approverPhone).doesNotContain("0240000201");

    FakeAccountResolver.name("0240000207", "Kofi Mensah");
    ResponseEntity<Map<String, Object>> offGoal =
        create(session, goalBody("0240000207", "OFF", null, null), uniqueKey());
    assertThat(offGoal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID offGoalId = UUID.fromString((String) offGoal.getBody().get("id"));
    ResponseEntity<Map<String, Object>> offHold =
        updateSettings(session, offGoalId, Map.of("holdWindowMinutes", 25));
    assertThat(offHold.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(offHold.getBody()).isNotNull();
    assertThat(offHold.getBody().get("holdWindowMinutes")).isEqualTo(25);

    String creationApproverPhone = "0240000205";
    FakeAccountResolver.name("0240000204", "Kofi Mensah");
    ResponseEntity<Map<String, Object>> createdWithApprover =
        create(
            session,
            goalBody("0240000204", "ALWAYS", "Yaw Mensah", creationApproverPhone),
            uniqueKey());
    assertThat(createdWithApprover.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(createdWithApprover.getBody()).isNotNull();
    assertThat(createdWithApprover.getBody().get("secondApproverName")).isEqualTo("Yaw Mensah");
    assertThat(createdWithApprover.getBody()).doesNotContainKey("secondApproverPhone");
    UUID createdWithApproverId = UUID.fromString((String) createdWithApprover.getBody().get("id"));
    Map<String, Object> creationStored =
        jdbc.queryForMap(
            """
            select second_approver_phone_ciphertext, second_approver_phone_hash,
                   second_approver_set_at
            from goals where id = ?
            """,
            createdWithApproverId);
    assertThat((byte[]) creationStored.get("second_approver_phone_ciphertext"))
        .isNotEqualTo(creationApproverPhone.getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) creationStored.get("second_approver_phone_hash")).hasSize(32);
    assertThat(creationStored.get("second_approver_set_at")).isNotNull();
    ResponseEntity<Map<String, Object>> existingHold =
        updateSettings(session, createdWithApproverId, Map.of("holdWindowMinutes", 45));
    assertThat(existingHold.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(existingHold.getBody()).isNotNull();
    assertThat(existingHold.getBody().get("holdWindowMinutes")).isEqualTo(45);
    assertThat(existingHold.getBody().get("secondApproverName")).isEqualTo("Yaw Mensah");

    ResponseEntity<Map<String, Object>> sameAsOrganizer =
        updateSettings(
            session,
            goalId,
            Map.of("secondApproverName", "Ama Mensah", "secondApproverPhone", "0240000201"));
    assertThat(sameAsOrganizer.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(sameAsOrganizer.getBody()).isNotNull();
    assertThat(sameAsOrganizer.getBody().get("code")).isEqualTo("APPROVER_MUST_DIFFER");

    ResponseEntity<Map<String, Object>> zeroHold =
        updateSettings(session, goalId, Map.of("holdWindowMinutes", 0));
    assertThat(zeroHold.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(zeroHold.getBody()).isNotNull();
    assertThat(zeroHold.getBody().get("code")).isEqualTo("HOLD_REQUIRED");

    jdbc.update(
        """
        insert into payouts
            (goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             state, idempotency_key)
        values (?, ?, 1000, 0, 1000, 'REQUESTED', ?)
        """,
        goalId,
        session.userId(),
        "payout-" + UUID.randomUUID());
    ResponseEntity<Map<String, Object>> locked =
        updateSettings(
            session,
            goalId,
            Map.of("secondApproverName", "Other", "secondApproverPhone", "0240000206"));
    assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(locked.getBody()).isNotNull();
    assertThat(locked.getBody().get("code")).isEqualTo("PAYOUT_SETTINGS_LOCKED");

    assertThatThrownBy(
            () -> jdbc.update("update goals set hold_window_minutes = 50 where id = ?", goalId))
        .hasMessageContaining("payout settings are locked");
  }

  private Map<String, Object> goalBody(
      String destinationPhone,
      String secondApprover,
      String secondApproverName,
      String secondApproverPhone) {
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Ama's funeral");
    body.put("template", "FUNERAL");
    body.put("targetType", "FIXED");
    body.put("fixedAmountPesewas", 5_000L);
    body.put("deadline", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    body.put("destinationType", "BENEFICIARY_MOMO");
    body.put("destinationPhone", destinationPhone);
    body.put("network", "MTN");
    body.put("secondApprover", secondApprover);
    body.put("holdWindowMinutes", 60);
    body.put("surplusRule", "PAY_BENEFICIARY");
    if (secondApproverName != null) {
      body.put("secondApproverName", secondApproverName);
    }
    if (secondApproverPhone != null) {
      body.put("secondApproverPhone", secondApproverPhone);
    }
    return body;
  }

  private Session session(String phone, String legalName) {
    requestOtp(phone);
    ResponseEntity<Map<String, Object>> verified =
        verify(phone, FakeSmsSender.latestCode(), legalName);
    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    String setCookie = verified.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).startsWith("tgthr_session=");
    String token = setCookie.substring("tgthr_session=".length(), setCookie.indexOf(';'));
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

  private ResponseEntity<Map<String, Object>> create(
      Session session, Map<String, Object> body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/goals",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> updateSettings(
      Session session, UUID goalId, Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + session.token());
    return rest.exchange(
        "/organizer/goals/{id}/payout-settings",
        HttpMethod.PATCH,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {},
        goalId);
  }

  private static String uniqueKey() {
    return "goal-" + UUID.randomUUID();
  }

  private record Session(String token, UUID userId) {}
}
