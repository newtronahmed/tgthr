package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
