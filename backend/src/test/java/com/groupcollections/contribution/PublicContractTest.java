package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
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

class PublicContractTest extends ApiIntegrationTest {

  private static final String PAYER_PHONE = "0240000001";

  @Test
  @Criterion("AC-CONTRIB-011")
  void publicResponsesNeverContainThePayerPhoneNumber() {
    String slug = insertGoal("contract", "OPEN", null);

    ResponseEntity<String> accepted = createContributionRaw(slug, 5_000, PAYER_PHONE);
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(accepted.getBody()).doesNotContain(PAYER_PHONE);

    ResponseEntity<String> rejected = createContributionRaw(slug, 999, PAYER_PHONE);
    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(rejected.getBody()).doesNotContain(PAYER_PHONE);

    String contributionId = contributionId(accepted);
    ResponseEntity<String> status =
        rest.exchange(
            "/public/contributions/{id}", HttpMethod.GET, null, String.class, contributionId);
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status.getBody()).doesNotContain(PAYER_PHONE);
  }

  @Test
  @Criterion("AC-CONTRIB-010")
  void anonymousFeedHidesTheContributorName() {
    String slug = insertGoal("feed", "OPEN", null);

    confirm(createContributionWithName(slug, "Ama", true));
    confirm(createContributionWithName(slug, "Kofi", false));

    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/public/goals/{slug}",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {},
            slug);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> goal = response.getBody();
    assertThat(goal).isNotNull();
    assertThat(goal.get("raisedPesewas")).isEqualTo(10_000);
    assertThat(goal.get("contributorCount")).isEqualTo(2);
    assertThat(goal.get("payoutRules")).isInstanceOf(Map.class);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> feed = (List<Map<String, Object>>) goal.get("feed");
    assertThat(feed).hasSize(2);

    Map<String, Object> anonymized =
        feed.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("anonymous")))
            .findFirst()
            .orElseThrow();
    assertThat(anonymized.get("displayName")).isNull();
    assertThat(anonymized.get("amountPesewas")).isEqualTo(5_000);

    Map<String, Object> named =
        feed.stream()
            .filter(item -> !Boolean.TRUE.equals(item.get("anonymous")))
            .findFirst()
            .orElseThrow();
    assertThat(named.get("displayName")).isEqualTo("Kofi");
  }

  private Map<String, Object> createContributionWithName(
      String slug, String displayName, boolean anonymous) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "feed-" + UUID.randomUUID());
    Map<String, Object> body =
        Map.of(
            "amountPesewas",
            5_000,
            "momoNumber",
            PAYER_PHONE,
            "network",
            "MTN",
            "displayName",
            displayName,
            "anonymous",
            anonymous);
    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/public/goals/{slug}/contributions",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            new ParameterizedTypeReference<>() {},
            slug);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    return response.getBody();
  }

  private void confirm(Map<String, Object> contribution) {
    assertThat(contribution).isNotNull();
    String id = (String) contribution.get("id");
    long total = ((Number) contribution.get("totalPesewas")).longValue();
    assertThat(PAYSTACK.sendSuccessCallback(webhookUrl(), id, total)).isEqualTo(204);
  }

  private static String contributionId(ResponseEntity<String> response) {
    String body = response.getBody();
    assertThat(body).isNotNull();
    int start = body.indexOf("\"id\":\"") + 6;
    return body.substring(start, body.indexOf('"', start));
  }
}
