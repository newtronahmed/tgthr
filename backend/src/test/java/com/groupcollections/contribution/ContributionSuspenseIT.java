package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.alerts.Alert;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.RecordingAlertPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
                  and amount_pesewas = 5000
                """,
                contributionId))
        .isEqualTo(1);
    assertThat(
            contributions.findRequiringReconciliation().stream()
                .anyMatch(item -> item.id().equals(contributionId)))
        .isTrue();
    assertThat(RecordingAlertPublisher.alerts())
        .anyMatch(alert -> alert.type() == Alert.AlertType.RECONCILIATION_REQUIRED);
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }
}
