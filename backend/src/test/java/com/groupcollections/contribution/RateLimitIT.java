package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RateLimitIT extends ApiIntegrationTest {

  @TestConfiguration
  static class LimitConfiguration {
    @Bean
    @Primary
    RateLimiter rateLimiter() {
      return new RateLimiter(Clock.systemUTC(), true, 1_000, 2);
    }
  }

  @Test
  @Criterion("AC-CONTRIB-012")
  void blocksAfterThePerPhoneLimit() {
    String slug = insertGoal("rate-limit", "OPEN", null);

    assertThat(createContribution(slug, 5_000, "0240000001").getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(createContribution(slug, 5_000, "0240000001").getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    ResponseEntity<Map<String, Object>> blocked = createContribution(slug, 5_000, "0240000001");
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(blocked.getBody()).containsEntry("code", "RATE_LIMITED");
    assertThat(PAYSTACK.promptCount()).isEqualTo(2);

    ResponseEntity<Map<String, Object>> otherPhone = createContribution(slug, 5_000, "0240000002");
    assertThat(otherPhone.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }
}
