package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

  private static String contributionId(ResponseEntity<String> response) {
    String body = response.getBody();
    assertThat(body).isNotNull();
    int start = body.indexOf("\"id\":\"") + 6;
    return body.substring(start, body.indexOf('"', start));
  }
}
