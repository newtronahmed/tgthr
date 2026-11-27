package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * T198: the per-IP limit keys on the forwarded caller address, so one abusive caller cannot exhaust
 * another caller's budget.
 *
 * <p>{@code server.forward-headers-strategy: framework} makes the backend honour {@code
 * X-Forwarded-For}, which means the BFF is the trust boundary: it must forward only the address the
 * platform supplies, never the browser's own header ({@code lib/client-ip.ts}).
 *
 * <p>One sequential test on purpose. The limiter is an in-memory singleton, so separate test
 * methods would share buckets and pass or fail depending on execution order.
 */
class TrustedClientIpIT extends ApiIntegrationTest {

  @TestConfiguration
  static class LimitConfiguration {
    @Bean
    @Primary
    RateLimiter rateLimiter() {
      // Two per IP per minute, effectively unlimited per phone, so only the IP bucket can trip.
      return new RateLimiter(Clock.systemUTC(), true, 2, 1_000);
    }
  }

  @Test
  void thePerIpLimitKeysOnTheForwardedAddressAndIsPerCaller() {
    String slug = insertGoal("trusted-ip", "OPEN", null);

    // First caller: two allowed, the third blocked, even on a fresh phone.
    assertThat(quote(slug, "0240000031", "203.0.113.9").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(quote(slug, "0240000032", "203.0.113.9").getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<Map<String, Object>> blocked = quote(slug, "0240000033", "203.0.113.9");
    assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(blocked.getBody()).containsEntry("code", "RATE_LIMITED");

    // A different caller is unaffected by the exhausted bucket.
    assertThat(quote(slug, "0240000034", "198.51.100.7").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<Map<String, Object>> quote(String slug, String phone, String forwarded) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("x-forwarded-for", forwarded);
    return rest.exchange(
        "/public/goals/{slug}/contribution-quote",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("phone", phone, "amountPesewas", 5_000), headers),
        new ParameterizedTypeReference<>() {},
        slug);
  }
}
