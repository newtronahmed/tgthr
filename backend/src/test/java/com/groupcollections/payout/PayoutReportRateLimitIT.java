package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.identity.SmsSender;
import com.groupcollections.security.PhoneCipher;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakeSmsSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Abuse coverage for the public payout report endpoint (AC-PAYOUT-025/T361). */
@Import(PayoutReportRateLimitIT.ReportTestConfiguration.class)
@TestPropertySource(
    properties = {
      "collections.payout.enabled=true",
      "collections.rate-limit.enabled=true",
      "collections.rate-limit.payout-report-per-ip-per-minute=1"
    })
class PayoutReportRateLimitIT extends ApiIntegrationTest {

  @TestConfiguration
  static class ReportTestConfiguration {
    @Bean
    @Primary
    SmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }

    @Bean
    @Primary
    PayoutFeeProvider fakePayoutFeeProvider() {
      return new FakePayoutFeeProvider();
    }

    @Bean
    @Primary
    PayoutReportRateLimiter payoutReportRateLimiter(Clock clock, MeterRegistry meters) {
      return new PayoutReportRateLimiter(clock, true, 1, meters);
    }
  }

  @Autowired PhoneCipher phones;

  @Test
  @Criterion("AC-PAYOUT-025")
  void repeatedReportsFromOneIpAreRateLimited() {
    String slug = insertGoal("report-spam", "OPEN", null);
    UUID goalId = jdbc.queryForObject("select id from goals where slug = ?", UUID.class, slug);
    UUID requester = insertUser("0240000901");
    UUID payoutId = insertHeldPayout(goalId, requester);
    double limitedBefore = counter("payout.report.rate_limited");

    assertThat(report(slug, payoutId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    ResponseEntity<Map<String, Object>> limited = report(slug, payoutId);
    assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(limited.getBody()).isNotNull();
    assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");
    assertThat(counter("payout.report.rate_limited") - limitedBefore).isEqualTo(1.0);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from payout_reports where payout_id = ?", Integer.class, payoutId))
        .isEqualTo(1);
  }

  private ResponseEntity<Map<String, Object>> report(String slug, UUID payoutId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/public/goals/{slug}/payouts/{payoutId}/report",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("reason", "repeated spam"), headers),
        new ParameterizedTypeReference<>() {},
        slug,
        payoutId);
  }

  private UUID insertUser(String phone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into users (id, phone_ciphertext, phone_hash) values (?, ?, ?)",
        id,
        phones.encrypt(phone),
        phones.hash(phone));
    return id;
  }

  private UUID insertHeldPayout(UUID goalId, UUID requester) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into payouts
            (id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
             state, idempotency_key, hold_expires_at)
        values (?, ?, ?, 1_000, 0, 1_000, 'HOLD', ?, now() + interval '1 hour')
        """,
        id,
        goalId,
        requester,
        "report-spam-" + id);
    return id;
  }
}
