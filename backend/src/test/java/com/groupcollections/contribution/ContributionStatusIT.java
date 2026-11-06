package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ContributionStatusIT extends ApiIntegrationTest {

  @Test
  @Criterion("AC-CONTRIB-013")
  void receiptAppearsOnlyWhenConfirmed() {
    String slug = insertGoal("receipt", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String contributionId = (String) created.get("id");
    long total = ((Number) created.get("totalPesewas")).longValue();

    Map<String, Object> pending = getContribution(contributionId);
    assertThat(pending.get("state")).isEqualTo("PENDING");
    assertThat(pending.get("receipt")).isNull();
    assertThat(pending.get("amountPesewas")).isEqualTo(5_000);
    assertThat(pending)
        .containsKeys("processingFeePesewas", "platformFeePesewas", "feePesewas", "totalPesewas");

    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), contributionId, total)).isEqualTo(204);

    Instant before = Instant.now().minusSeconds(5);
    Map<String, Object> confirmed = getContribution(contributionId);
    assertThat(confirmed.get("state")).isEqualTo("CONFIRMED");
    assertThat(confirmed.get("amountPesewas")).isEqualTo(5_000);
    assertThat(confirmed.get("processingFeePesewas"))
        .isEqualTo(created.get("processingFeePesewas"));
    assertThat(confirmed.get("platformFeePesewas")).isEqualTo(created.get("platformFeePesewas"));
    assertThat(confirmed.get("feePesewas")).isEqualTo(created.get("feePesewas"));
    assertThat(confirmed.get("totalPesewas")).isEqualTo(created.get("totalPesewas"));
    assertThat(confirmed.get("receipt")).isInstanceOf(Map.class);
    Map<?, ?> receipt = (Map<?, ?>) confirmed.get("receipt");
    assertThat(receipt.get("reference")).isEqualTo(contributionId);
    Instant confirmedAt = Instant.parse((String) receipt.get("confirmedAt"));
    assertThat(confirmedAt).isAfter(before);
    assertThat(confirmedAt).isBefore(Instant.now().plusSeconds(5));
  }

  @Test
  void unknownContributionReturns404Problem() {
    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/public/contributions/{id}",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {},
            UUID.randomUUID());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("code", "CONTRIBUTION_NOT_FOUND");
  }

  private Map<String, Object> getContribution(String contributionId) {
    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/public/contributions/{id}",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {},
            contributionId);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }
}
