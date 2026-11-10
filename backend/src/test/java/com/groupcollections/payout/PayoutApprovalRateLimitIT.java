package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.FakeAccountResolver;
import com.groupcollections.support.FakePayoutFeeProvider;
import com.groupcollections.support.FakeSmsSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@Import(PayoutApprovalRateLimitIT.RateLimitTestConfiguration.class)
@TestPropertySource(properties = "collections.payout.enabled=true")
class PayoutApprovalRateLimitIT extends ApiIntegrationTest {

  @TestConfiguration
  static class RateLimitTestConfiguration {
    @Bean
    @Primary
    SmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }

    @Bean
    @Primary
    AccountResolver fakeAccountResolver() {
      return new FakeAccountResolver();
    }

    @Bean
    @Primary
    PayoutFeeProvider fakePayoutFeeProvider() {
      return new FakePayoutFeeProvider();
    }

    @Bean
    @Primary
    PayoutApprovalRateLimiter payoutApprovalRateLimiter(Clock clock, MeterRegistry meters) {
      return new PayoutApprovalRateLimiter(clock, true, 1, 100, meters);
    }
  }

  @Autowired PayoutApprovalRateLimiter rateLimiter;

  @BeforeEach
  void reset() {
    rateLimiter.clear();
    FakeSmsSender.reset();
  }

  @Test
  void allPublicApprovalRoutesAreRateLimitedBeforeTokenLookup() {
    List<Route> routes =
        List.of(
            new Route("/payout-approvals/not-a-token", HttpMethod.GET, null),
            new Route(
                "/payout-approvals/not-a-token/verify", HttpMethod.POST, "{\"code\":\"123456\"}"),
            new Route("/payout-approvals/not-a-token/resend", HttpMethod.POST, null),
            new Route("/payout-approvals/not-a-token/decline", HttpMethod.POST, null));

    for (Route route : routes) {
      rateLimiter.clear();
      assertThat(call(route).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(call(route).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
  }

  private ResponseEntity<String> call(Route route) {
    HttpHeaders headers = new HttpHeaders();
    if (route.body() != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return rest.exchange(
        route.path(), route.method(), new HttpEntity<>(route.body(), headers), String.class);
  }

  private record Route(String path, HttpMethod method, String body) {}
}
