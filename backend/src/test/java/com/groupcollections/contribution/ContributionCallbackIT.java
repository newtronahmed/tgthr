package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.util.List;
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
            select account, entry_type, direction, amount_pesewas
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
                        ? ((Number) entry.get("amount_pesewas")).longValue()
                        : -((Number) entry.get("amount_pesewas")).longValue())
            .sum();
    assertThat(net).isZero();

    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("PARTNER_HELD");
              assertThat(entry.get("direction")).isEqualTo("DEBIT");
              assertThat(((Number) entry.get("amount_pesewas")).longValue()).isEqualTo(total);
            });
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("GOAL_POOL");
              assertThat(entry.get("entry_type")).isEqualTo("CONTRIBUTION");
              assertThat(entry.get("direction")).isEqualTo("CREDIT");
              assertThat(((Number) entry.get("amount_pesewas")).longValue()).isEqualTo(5_000);
            });
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("FEE");
              assertThat(((Number) entry.get("amount_pesewas")).longValue())
                  .isEqualTo(processingFee);
            });
    assertThat(entries)
        .anySatisfy(
            entry -> {
              assertThat(entry.get("account")).isEqualTo("PLATFORM_FEE");
              assertThat(((Number) entry.get("amount_pesewas")).longValue()).isEqualTo(platformFee);
            });

    Long raised =
        jdbc.queryForObject(
            """
            select coalesce(sum(amount_pesewas), 0)
            from ledger_entries
            where contribution_id = ? and entry_type = 'CONTRIBUTION' and direction = 'CREDIT'
            """,
            Long.class,
            id);
    assertThat(raised).isEqualTo(5_000);
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
