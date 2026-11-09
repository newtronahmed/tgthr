package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.alerts.Alert;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.RecordingAlertPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class ContributionSuspenseIT extends ApiIntegrationTest {

  @Autowired private ContributionRepository contributions;

  @Test
  @Criterion("INV-LEDGER-008")
  void lateSuccessAfterClosureParksInSuspenseAndFlagsReconciliation() {
    String slug = insertGoal("suspense", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000007").getBody();
    assertThat(created).isNotNull();
    assertThat(created.get("state")).isEqualTo("FAILED");
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();

    jdbc.update("update goals set status = 'CLOSED' where slug = ?", slug);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);

    Map<String, Object> stored =
        jdbc.queryForMap(
            "select state, requires_reconciliation from contributions where id = ?",
            contributionId);
    assertThat(stored.get("state")).isEqualTo("CONFIRMED");
    assertThat(stored.get("requires_reconciliation")).isEqualTo(true);

    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and account = 'GOAL_POOL'",
                contributionId))
        .isZero();
    assertThat(
            count(
                """
                select count(*) from ledger_entries
                where contribution_id = ? and account = 'SUSPENSE'
                  and entry_type = 'SUSPENSE_HOLD' and direction = 'CREDIT'
                  and amount_minor_units = 5000
                """,
                contributionId))
        .isEqualTo(1);
    assertThat(
            contributions.findRequiringReconciliation().stream()
                .anyMatch(item -> item.id().equals(contributionId)))
        .isTrue();
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.RECONCILIATION_REQUIRED);
    ResponseEntity<Map<String, Object>> publicGoal =
        rest.exchange(
            "/public/goals/{slug}",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(new org.springframework.http.HttpHeaders()),
            new org.springframework.core.ParameterizedTypeReference<>() {},
            slug);
    assertThat(publicGoal.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.OK);
    assertThat(publicGoal.getBody()).isNotNull();
    assertThat(((Number) publicGoal.getBody().get("raisedPesewas")).longValue()).isZero();
    assertThat(((Number) publicGoal.getBody().get("contributorCount")).longValue()).isZero();
    assertThat((java.util.List<?>) publicGoal.getBody().get("feed")).isEmpty();

    long processing = ((Number) created.get("processingFeePesewas")).longValue();
    long platform = ((Number) created.get("platformFeePesewas")).longValue();
    if (processing > 0) {
      assertThat(
              count(
                  "select count(*) from ledger_entries where contribution_id = ? and account = 'FEE' and amount_minor_units = ?",
                  contributionId,
                  processing))
          .isEqualTo(1);
    }
    if (platform > 0) {
      assertThat(
              count(
                  "select count(*) from ledger_entries where contribution_id = ? and account = 'PLATFORM_FEE' and amount_minor_units = ?",
                  contributionId,
                  platform))
          .isEqualTo(1);
    }
    int entries =
        count("select count(*) from ledger_entries where contribution_id = ?", contributionId);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    assertThat(
            count("select count(*) from ledger_entries where contribution_id = ?", contributionId))
        .isEqualTo(entries);
  }

  @Test
  void frozenGoalAlsoParksInSuspense() {
    String slug = insertGoal("suspense-frozen", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000007").getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();
    jdbc.update("update goals set status = 'FROZEN' where slug = ?", slug);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select requires_reconciliation from contributions where id = ?",
                Boolean.class,
                contributionId))
        .isTrue();
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and account = 'SUSPENSE'",
                contributionId))
        .isEqualTo(1);
  }

  @Test
  void closeAndConfirmRaceProducesExactlyOneLedgerOutcome() throws Exception {
    String slug = insertGoal("suspense-race", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    try {
      java.util.concurrent.Future<Integer> callback =
          pool.submit(
              () -> {
                start.await();
                return PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total);
              });
      java.util.concurrent.Future<Integer> close =
          pool.submit(
              () -> {
                start.await();
                return jdbc.update("update goals set status = 'CLOSED' where slug = ?", slug);
              });
      start.countDown();
      assertThat(callback.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(204);
      assertThat(close.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    int goalPool =
        count(
            "select count(*) from ledger_entries where contribution_id = ? and account = 'GOAL_POOL'",
            contributionId);
    int suspense =
        count(
            "select count(*) from ledger_entries where contribution_id = ? and account = 'SUSPENSE'",
            contributionId);
    assertThat(goalPool + suspense).isEqualTo(1);
    Boolean flagged =
        jdbc.queryForObject(
            "select requires_reconciliation from contributions where id = ?",
            Boolean.class,
            contributionId);
    assertThat(flagged).isEqualTo(suspense == 1);
  }

  @Test
  void pastDeadlineAlsoParksInSuspense() {
    String slug = insertGoal("suspense-deadline", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000007").getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();
    jdbc.update("update goals set deadline = now() - interval '1 minute' where slug = ?", slug);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select requires_reconciliation from contributions where id = ?",
                Boolean.class,
                contributionId))
        .isTrue();
  }

  @Test
  void openGoalConfirmationDoesNotFlagReconciliation() {
    String slug = insertGoal("suspense-normal", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    long total = ((Number) created.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId.toString(), total))
        .isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select requires_reconciliation from contributions where id = ?",
                Boolean.class,
                contributionId))
        .isFalse();
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and account = 'GOAL_POOL'",
                contributionId))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from ledger_entries where contribution_id = ? and account = 'SUSPENSE'",
                contributionId))
        .isZero();
  }

  @Test
  void suspenseEntryWithoutReconciliationFlagIsRejected() {
    String slug = insertGoal("suspense-guard", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    UUID contributionId = UUID.fromString((String) created.get("id"));
    UUID postingSet = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into ledger_entries
                        (posting_set_id, contribution_id, account, entry_type, direction,
                         amount_minor_units, currency_code)
                    values (?, ?, 'SUSPENSE', 'SUSPENSE_HOLD', 'CREDIT', 5000, 'GHS')
                    """,
                    postingSet,
                    contributionId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }
}
