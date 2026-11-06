package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ContributionApiIT extends ApiIntegrationTest {

  @Test
  @Criterion("AC-CONTRIB-001")
  void openGoalCreatesContributionAndRequestsPrompt() {
    String slug = insertGoal("goal", "OPEN", null);

    ResponseEntity<Map<String, Object>> response = createContribution(slug, 5_000, "0240000001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    Map<String, Object> status = response.getBody();
    assertThat(status).isNotNull();
    assertThat(status.get("state")).isEqualTo("PENDING");
    assertThat(status.get("amountPesewas")).isEqualTo(5_000);
    assertThat(status)
        .containsKeys(
            "id", "processingFeePesewas", "platformFeePesewas", "feePesewas", "totalPesewas");

    assertThat(PAYSTACK.promptCount()).isEqualTo(1);

    Map<String, Object> stored =
        jdbc.queryForMap(
            "select id, state from contributions where goal_id = (select id from goals where slug = ?)",
            slug);
    assertThat(stored.get("state")).isEqualTo("PENDING");
    assertThat(stored.get("id")).isEqualTo(UUID.fromString((String) status.get("id")));

    Integer transitions =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and from_state = 'INITIATED' and to_state = 'PENDING'
            """,
            Integer.class,
            stored.get("id"));
    assertThat(transitions).isEqualTo(1);
  }

  @Test
  @Criterion("AC-CONTRIB-009")
  void retryAfterTerminalStateCreatesANewContribution() {
    String slug = insertGoal("retry", "OPEN", null);

    ResponseEntity<Map<String, Object>> failed = createContribution(slug, 5_000, "0240000002");
    assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(failed.getBody()).isNotNull();
    assertThat(failed.getBody().get("state")).isEqualTo("FAILED");
    String failedId = (String) failed.getBody().get("id");

    ResponseEntity<Map<String, Object>> retried = createContribution(slug, 5_000, "0240000001");
    assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(retried.getBody()).isNotNull();
    assertThat(retried.getBody().get("state")).isEqualTo("PENDING");
    String retriedId = (String) retried.getBody().get("id");
    assertThat(retriedId).isNotEqualTo(failedId);

    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?",
                String.class,
                UUID.fromString(failedId)))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?",
                String.class,
                UUID.fromString(retriedId)))
        .isEqualTo("PENDING");
    assertThat(PAYSTACK.promptCount()).isEqualTo(2);
    assertThat(PAYSTACK.chargeBodies()).hasSize(2);
    assertThat(PAYSTACK.chargeBodies().get(0)).isNotEqualTo(PAYSTACK.chargeBodies().get(1));
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from contributions where goal_id = (select id from goals where slug = ?)",
            Integer.class,
            slug);
    assertThat(rows).isEqualTo(2);

    String expiredSlug = insertGoal("retry-expired", "OPEN", null);
    ResponseEntity<Map<String, Object>> expired =
        createContribution(expiredSlug, 5_000, "0240000001");
    assertThat(expired.getBody()).isNotNull();
    UUID expiredId = UUID.fromString((String) expired.getBody().get("id"));
    jdbc.update("update contributions set state = 'EXPIRED' where id = ?", expiredId);

    ResponseEntity<Map<String, Object>> afterExpiry =
        createContribution(expiredSlug, 5_000, "0240000001");
    assertThat(afterExpiry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(afterExpiry.getBody()).isNotNull();
    assertThat(afterExpiry.getBody().get("state")).isEqualTo("PENDING");
    assertThat(afterExpiry.getBody().get("id")).isNotEqualTo(expiredId.toString());
    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, expiredId))
        .isEqualTo("EXPIRED");
  }

  @Test
  @Criterion("AC-CONTRIB-019")
  void rejectedPromptRequestMarksTheContributionFailed() {
    String slug = insertGoal("prompt-rejected", "OPEN", null);

    ResponseEntity<Map<String, Object>> response = createContribution(slug, 5_000, "0240000004");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("state")).isEqualTo("FAILED");
    UUID id = UUID.fromString((String) response.getBody().get("id"));
    Integer ledger =
        jdbc.queryForObject(
            "select count(*) from ledger_entries where contribution_id = ?", Integer.class, id);
    assertThat(ledger).isZero();
    Integer rejectedEvents =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and to_state = 'FAILED'
            """,
            Integer.class,
            id);
    assertThat(rejectedEvents).isEqualTo(1);
  }

  @Test
  void contributionsAboveTheMaximumAreRejected() {
    String slug = insertGoal("maximum", "OPEN", null);

    ResponseEntity<Map<String, Object>> response =
        createContribution(slug, 10_000_001, "0240000001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
    assertThat(PAYSTACK.promptCount()).isZero();

    ResponseEntity<Map<String, Object>> atLimit =
        createContribution(slug, 10_000_000, "0240000001");
    assertThat(atLimit.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(atLimit.getBody()).isNotNull();
    assertThat(atLimit.getBody().get("state")).isEqualTo("PENDING");
  }

  @Test
  @Criterion("AC-CONTRIB-002")
  void fixedGoalRejectsADifferentAmount() {
    UUID goalId = UUID.randomUUID();
    String slug = "fixed-" + goalId;
    jdbc.update(
        """
        insert into goals
            (id, slug, title, template, target_type, fixed_amount_pesewas, destination_type,
             second_approver, hold_window_minutes, surplus_rule)
        values (?, ?, 'Rent support', 'RENT', 'FIXED', 5_000, 'BENEFICIARY_MOMO', 'OFF', 0,
                'PAY_BENEFICIARY')
        """,
        goalId,
        slug);

    ResponseEntity<Map<String, Object>> response = createContribution(slug, 4_000, "0240000001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.getBody()).containsEntry("code", "AMOUNT_NOT_ALLOWED");

    Integer stored =
        jdbc.queryForObject(
            "select count(*) from contributions where goal_id = ?", Integer.class, goalId);
    assertThat(stored).isZero();
    assertThat(PAYSTACK.promptCount()).isZero();
  }

  @Test
  void invalidRequestsReturnProblemDetails() {
    String slug = insertGoal("validation", "OPEN", null);
    Map<String, Object> body =
        Map.of("amountPesewas", 5_000, "momoNumber", "0240000001", "network", "MTN");

    HttpHeaders shortKey = new HttpHeaders();
    shortKey.setContentType(MediaType.APPLICATION_JSON);
    shortKey.set("Idempotency-Key", "short");
    ResponseEntity<Map<String, Object>> shortKeyResponse =
        rest.exchange(
            "/public/goals/{slug}/contributions",
            HttpMethod.POST,
            new HttpEntity<>(body, shortKey),
            new ParameterizedTypeReference<>() {},
            slug);
    assertThat(shortKeyResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(shortKeyResponse.getBody()).containsEntry("code", "INVALID_REQUEST");

    HttpHeaders missingKey = new HttpHeaders();
    missingKey.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map<String, Object>> missingKeyResponse =
        rest.exchange(
            "/public/goals/{slug}/contributions",
            HttpMethod.POST,
            new HttpEntity<>(body, missingKey),
            new ParameterizedTypeReference<>() {},
            slug);
    assertThat(missingKeyResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(missingKeyResponse.getBody()).containsEntry("code", "INVALID_REQUEST");

    HttpHeaders malformedHeaders = new HttpHeaders();
    malformedHeaders.setContentType(MediaType.APPLICATION_JSON);
    malformedHeaders.set("Idempotency-Key", "idem-" + UUID.randomUUID());
    ResponseEntity<Map<String, Object>> malformedResponse =
        rest.exchange(
            "/public/goals/{slug}/contributions",
            HttpMethod.POST,
            new HttpEntity<>("{\"amountPesewas\":", malformedHeaders),
            new ParameterizedTypeReference<>() {},
            slug);
    assertThat(malformedResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(malformedResponse.getBody()).containsEntry("code", "INVALID_REQUEST");

    assertThat(PAYSTACK.promptCount()).isZero();
    Integer stored =
        jdbc.queryForObject(
            "select count(*) from contributions where goal_id = (select id from goals where slug = ?)",
            Integer.class,
            slug);
    assertThat(stored).isZero();
  }

  @Test
  @Criterion("AC-CONTRIB-015")
  void retryingWithTheSameIdempotencyKeyReturnsTheOriginalContribution() {
    String slug = insertGoal("open", "OPEN", null);
    String idempotencyKey = "retry-" + UUID.randomUUID();

    ResponseEntity<Map<String, Object>> first =
        createContribution(slug, 5_000, "0240000001", idempotencyKey);
    ResponseEntity<Map<String, Object>> retry =
        createContribution(slug, 5_000, "0240000001", idempotencyKey);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(first.getBody()).isNotNull();
    String originalId = (String) first.getBody().get("id");
    assertThat(retry.getBody().get("id")).isEqualTo(originalId);

    assertThat(PAYSTACK.promptCount()).isEqualTo(1);

    Integer stored =
        jdbc.queryForObject(
            "select count(*) from contributions where goal_id = (select id from goals where slug = ?)",
            Integer.class,
            slug);
    assertThat(stored).isEqualTo(1);
  }

  @Test
  @Criterion("AC-CONTRIB-012")
  void closedFrozenAndPastDeadlineGoalsRejectContributions() {
    String closed = insertGoal("closed", "CLOSED", null);
    String frozen = insertGoal("frozen", "FROZEN", null);
    String pastDeadline = insertGoal("expired", "OPEN", Instant.now().minusSeconds(3_600));

    for (String slug : new String[] {closed, frozen, pastDeadline}) {
      ResponseEntity<Map<String, Object>> response = createContribution(slug, 5_000, "0240000001");

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody()).containsEntry("code", "GOAL_NOT_ACCEPTING");
    }

    Integer stored =
        jdbc.queryForObject(
            """
            select count(*) from contributions c
            join goals g on g.id = c.goal_id
            where g.slug in (?, ?, ?)
            """,
            Integer.class,
            closed,
            frozen,
            pastDeadline);
    assertThat(stored).isZero();
    assertThat(PAYSTACK.promptCount()).isZero();
  }
}
