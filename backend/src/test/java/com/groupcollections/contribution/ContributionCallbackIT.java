package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.alerts.Alert;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.RecordingAlertPublisher;
import java.util.List;
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

class ContributionCallbackIT extends ApiIntegrationTest {

  @Test
  @Criterion("AC-CONTRIB-007")
  void invalidSignatureIsRejectedWithoutStateChange() {
    String slug = insertGoal("callback", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    long total = ((Number) created.get("totalPesewas")).longValue();

    int status =
        PAYSTACK.sendCallbackWithBadSignature(webhookUrl(), contributionId, "success", total);

    assertThat(status).isEqualTo(401);

    UUID id = UUID.fromString(contributionId);
    String state =
        jdbc.queryForObject("select state from contributions where id = ?", String.class, id);
    assertThat(state).isEqualTo("PENDING");

    Integer confirmedEvents =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and to_state = 'CONFIRMED'
            """,
            Integer.class,
            id);
    assertThat(confirmedEvents).isZero();

    Integer ledgerEntries =
        jdbc.queryForObject(
            "select count(*) from ledger_entries where contribution_id = ?", Integer.class, id);
    assertThat(ledgerEntries).isZero();
  }

  @Test
  @Criterion("AC-CONTRIB-005")
  void successConfirmsAndPostsBalancedLedgerEntries() {
    String slug = insertGoal("success", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    UUID id = UUID.fromString(contributionId);
    long total = ((Number) created.get("totalPesewas")).longValue();
    long processingFee = ((Number) created.get("processingFeePesewas")).longValue();
    long platformFee = ((Number) created.get("platformFeePesewas")).longValue();

    int status = PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total);

    assertThat(status).isEqualTo(204);
    String state =
        jdbc.queryForObject("select state from contributions where id = ?", String.class, id);
    assertThat(state).isEqualTo("CONFIRMED");

    List<Map<String, Object>> entries =
        jdbc.queryForList(
            """
            select account, entry_type, direction, amount_minor_units
            from ledger_entries
            where contribution_id = ?
            """,
            id);
    assertThat(entries).hasSize(4);

    long net =
        entries.stream()
            .mapToLong(
                entry ->
                    "DEBIT".equals(entry.get("direction"))
                        ? ((Number) entry.get("amount_minor_units")).longValue()
                        : -((Number) entry.get("amount_minor_units")).longValue())
            .sum();
    assertThat(net).isZero();

    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("PARTNER_HELD");
              assertThat(entry.get("direction")).isEqualTo("DEBIT");
              assertThat(((Number) entry.get("amount_minor_units")).longValue()).isEqualTo(total);
            });
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("GOAL_POOL");
              assertThat(entry.get("entry_type")).isEqualTo("CONTRIBUTION");
              assertThat(entry.get("direction")).isEqualTo("CREDIT");
              assertThat(((Number) entry.get("amount_minor_units")).longValue()).isEqualTo(5_000);
            });
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("FEE");
              assertThat(((Number) entry.get("amount_minor_units")).longValue())
                  .isEqualTo(processingFee);
            });
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("PLATFORM_FEE");
              assertThat(((Number) entry.get("amount_minor_units")).longValue())
                  .isEqualTo(platformFee);
            });

    Long raised =
        jdbc.queryForObject(
            """
            select coalesce(sum(amount_minor_units), 0)
            from ledger_entries
            where contribution_id = ? and entry_type = 'CONTRIBUTION' and direction = 'CREDIT'
            """,
            Long.class,
            id);
    assertThat(raised).isEqualTo(5_000);
  }

  @Test
  @Criterion("INV-STATE-002")
  void ledgerEntriesExistOnlyForConfirmedContributions() {
    String slug = insertGoal("inv-ledger-state", "OPEN", null);

    Map<String, Object> pending = createContribution(slug, 5_000, "0240000001").getBody();
    UUID pendingId = UUID.fromString((String) pending.get("id"));
    assertThat(ledgerCount(pendingId)).isZero();

    Map<String, Object> failed = createContribution(slug, 5_000, "0240000001").getBody();
    UUID failedId = UUID.fromString((String) failed.get("id"));
    long failedTotal = ((Number) failed.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendFailedCallback(webhookUrl(), failedId.toString(), failedTotal))
        .isEqualTo(204);
    assertThat(ledgerCount(failedId)).isZero();

    Map<String, Object> confirmed = createContribution(slug, 5_000, "0240000001").getBody();
    UUID confirmedId = UUID.fromString((String) confirmed.get("id"));
    long confirmedTotal = ((Number) confirmed.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), confirmedId.toString(), confirmedTotal))
        .isEqualTo(204);
    assertThat(ledgerCount(confirmedId)).isEqualTo(4);

    Integer violations =
        jdbc.queryForObject(
            """
            select count(*) from contributions c
            where c.state not in ('CONFIRMED', 'REFUNDED')
              and exists (select 1 from ledger_entries le where le.contribution_id = c.id)
            """,
            Integer.class);
    assertThat(violations).isZero();
  }

  private Integer ledgerCount(UUID contributionId) {
    return jdbc.queryForObject(
        "select count(*) from ledger_entries where contribution_id = ?",
        Integer.class,
        contributionId);
  }

  @Test
  void malformedSignedWebhookReturnsProblemWithoutChange() {
    ResponseEntity<Map<String, Object>> malformed = postRaw("{\"event\":");
    assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(malformed.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(malformed.getBody()).containsEntry("code", "INVALID_WEBHOOK");

    ResponseEntity<Map<String, Object>> missingData = postRaw("{\"event\":\"charge.success\"}");
    assertThat(missingData.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(missingData.getBody()).containsEntry("code", "INVALID_WEBHOOK");
  }

  @Test
  void invalidWebhookPayloadsAreRejected() {
    String reference = UUID.randomUUID().toString();

    assertInvalidWebhook(
        """
        {"event":"charge.reversed","data":{"reference":"%s","status":"success","amount":1000,"currency":"GHS","paid_at":null}}
        """
            .formatted(reference));
    assertInvalidWebhook(
        """
        {"event":"charge.success","data":{"reference":"%s","status":"success","amount":1000,"currency":"USD","paid_at":null}}
        """
            .formatted(reference));
    assertInvalidWebhook(
        """
        {"event":"charge.success","data":{"reference":"%s","status":"failed","amount":1000,"currency":"GHS","paid_at":null}}
        """
            .formatted(reference));
  }

  private void assertInvalidWebhook(String body) {
    ResponseEntity<Map<String, Object>> response = postRaw(body);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).containsEntry("code", "INVALID_WEBHOOK");
  }

  private ResponseEntity<Map<String, Object>> postRaw(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("x-paystack-signature", PAYSTACK.signature(body));
    return rest.exchange(
        webhookUrl(),
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {});
  }

  @Test
  @Criterion("AC-CONTRIB-020")
  void unknownProviderReferenceIsAlertedWithoutChange() {
    String reference = UUID.randomUUID().toString();
    double before = counter("contributions.callback", "result", "unknown_reference");

    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), reference, 5_000)).isEqualTo(204);

    assertThat(counter("contributions.callback", "result", "unknown_reference"))
        .isEqualTo(before + 1);

    assertThat(RecordingAlertPublisher.alerts())
        .anySatisfy(
            alert -> {
              assertThat(alert.type()).isEqualTo(Alert.AlertType.UNKNOWN_REFERENCE);
              assertThat(alert.reference()).isEqualTo(reference);
            });
    Integer contributions =
        jdbc.queryForObject(
            "select count(*) from contributions where provider_reference = ?",
            Integer.class,
            reference);
    assertThat(contributions).isZero();
  }

  @Test
  @Criterion("AC-CONTRIB-021")
  void callbackAmountMismatchIsAlertedWithoutChange() {
    String slug = insertGoal("mismatch", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    UUID id = UUID.fromString(contributionId);
    long total = ((Number) created.get("totalPesewas")).longValue();
    double before = counter("contributions.callback", "result", "amount_mismatch");

    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total + 1))
        .isEqualTo(204);

    assertThat(counter("contributions.callback", "result", "amount_mismatch"))
        .isEqualTo(before + 1);

    assertThat(
            jdbc.queryForObject("select state from contributions where id = ?", String.class, id))
        .isEqualTo("PENDING");
    assertThat(ledgerCount(id)).isZero();
    assertThat(RecordingAlertPublisher.alerts())
        .anySatisfy(
            alert -> {
              assertThat(alert.type()).isEqualTo(Alert.AlertType.AMOUNT_MISMATCH);
              assertThat(alert.reference()).isEqualTo(contributionId);
            });
  }

  @Test
  @Criterion("AC-CONTRIB-018")
  void successBeforePendingConfirmsAndPostsOnce() {
    String slug = insertGoal("early-success", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    UUID id = UUID.fromString(contributionId);
    long total = ((Number) created.get("totalPesewas")).longValue();

    jdbc.update("update contributions set state = 'INITIATED' where id = ?", id);

    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total)).isEqualTo(204);

    assertConfirmedOnce(id);
    assertThat(PAYSTACK.promptCount()).isEqualTo(1);
  }

  @Test
  @Criterion("AC-CONTRIB-016")
  void lateSuccessAfterFailureOrExpiryConfirmsOnceAndPosts() {
    String failedSlug = insertGoal("late-failed", "OPEN", null);
    Map<String, Object> failed = createContribution(failedSlug, 5_000, "0240000001").getBody();
    String failedId = (String) failed.get("id");
    long failedTotal = ((Number) failed.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendFailedCallback(webhookUrl(), failedId, failedTotal)).isEqualTo(204);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), failedId, failedTotal)).isEqualTo(204);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), failedId, failedTotal)).isEqualTo(204);
    assertConfirmedOnce(UUID.fromString(failedId));

    String expiredSlug = insertGoal("late-expired", "OPEN", null);
    Map<String, Object> expired = createContribution(expiredSlug, 5_000, "0240000001").getBody();
    String expiredId = (String) expired.get("id");
    long expiredTotal = ((Number) expired.get("totalPesewas")).longValue();
    jdbc.update(
        "update contributions set state = 'EXPIRED' where id = ?", UUID.fromString(expiredId));

    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), expiredId, expiredTotal)).isEqualTo(204);
    assertConfirmedOnce(UUID.fromString(expiredId));

    assertThat(
            RecordingAlertPublisher.alerts().stream()
                .filter(alert -> alert.type() == Alert.AlertType.LATE_SUCCESS)
                .count())
        .isEqualTo(2);
  }

  private void assertConfirmedOnce(UUID id) {
    String state =
        jdbc.queryForObject("select state from contributions where id = ?", String.class, id);
    assertThat(state).isEqualTo("CONFIRMED");
    Integer entries =
        jdbc.queryForObject(
            "select count(*) from ledger_entries where contribution_id = ?", Integer.class, id);
    assertThat(entries).isEqualTo(4);
    Integer confirmedEvents =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and to_state = 'CONFIRMED'
            """,
            Integer.class,
            id);
    assertThat(confirmedEvents).isEqualTo(1);
  }

  @Test
  @Criterion("AC-CONTRIB-004")
  void duplicateSuccessCallbackPostsOnce() {
    String slug = insertGoal("duplicate", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    UUID id = UUID.fromString(contributionId);
    long total = ((Number) created.get("totalPesewas")).longValue();

    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total)).isEqualTo(204);
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total)).isEqualTo(204);

    String state =
        jdbc.queryForObject("select state from contributions where id = ?", String.class, id);
    assertThat(state).isEqualTo("CONFIRMED");

    Integer entries =
        jdbc.queryForObject(
            "select count(*) from ledger_entries where contribution_id = ?", Integer.class, id);
    assertThat(entries).isEqualTo(4);

    Integer postingSets =
        jdbc.queryForObject(
            "select count(distinct posting_set_id) from ledger_entries where contribution_id = ?",
            Integer.class,
            id);
    assertThat(postingSets).isEqualTo(1);

    Integer confirmedEvents =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and to_state = 'CONFIRMED'
            """,
            Integer.class,
            id);
    assertThat(confirmedEvents).isEqualTo(1);
  }

  @Test
  @Criterion("AC-CONTRIB-006")
  void failedCallbackMarksTheContributionFailedAndWritesNoLedgerEntries() {
    String slug = insertGoal("failed", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    long total = ((Number) created.get("totalPesewas")).longValue();

    int status = PAYSTACK.sendFailedCallback(webhookUrl(), contributionId, total);

    assertThat(status).isEqualTo(204);
    UUID id = UUID.fromString(contributionId);
    String state =
        jdbc.queryForObject("select state from contributions where id = ?", String.class, id);
    assertThat(state).isEqualTo("FAILED");

    Integer ledgerEntries =
        jdbc.queryForObject(
            "select count(*) from ledger_entries where contribution_id = ?", Integer.class, id);
    assertThat(ledgerEntries).isZero();

    Integer confirmedEvents =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and to_state = 'CONFIRMED'
            """,
            Integer.class,
            id);
    assertThat(confirmedEvents).isZero();
  }
}
