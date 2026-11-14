package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OperatorRateLimiterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T00:00:00Z"));
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final OperatorRateLimiter limiter = new OperatorRateLimiter(clock, true, 10, 20, meters);

  @Test
  void eleventhLoginFromOneIpIsRejected() {
    for (int attempt = 0; attempt < 10; attempt++) {
      limiter.checkLogin("203.0.113.1");
    }

    assertThatThrownBy(() -> limiter.checkLogin("203.0.113.1"))
        .isInstanceOfSatisfying(
            OperatorException.class,
            failure -> {
              assertThat(failure.status()).isEqualTo(429);
              assertThat(failure.code()).isEqualTo("RATE_LIMITED");
            });
    limiter.checkLogin("203.0.113.2");
    assertThat(limiter.windowCount()).isEqualTo(2);
    assertThat(counter("login")).isEqualTo(1);
  }

  @Test
  void mfaHasItsOwnLimitAndTheWindowResets() {
    for (int attempt = 0; attempt < 20; attempt++) {
      limiter.checkMfa("203.0.113.3");
    }
    assertThatThrownBy(() -> limiter.checkMfa("203.0.113.3")).isInstanceOf(OperatorException.class);
    limiter.checkLogin("203.0.113.3");

    clock.advance(Duration.ofSeconds(61));

    limiter.checkMfa("203.0.113.3");
    assertThat(limiter.windowCount()).isLessThanOrEqualTo(2);
  }

  @Test
  void expiredAddressesArePruned() {
    for (int attempt = 0; attempt < 10; attempt++) {
      limiter.checkLogin("203.0.113.4");
    }
    limiter.checkLogin("203.0.113.5");
    assertThat(limiter.windowCount()).isEqualTo(2);

    clock.advance(Duration.ofSeconds(61));
    limiter.checkLogin("203.0.113.6");

    assertThat(limiter.windowCount()).isEqualTo(1);
  }

  private double counter(String scope) {
    var counter = meters.find("operator.auth.rate_limited").tag("scope", scope).counter();
    return counter == null ? 0 : counter.count();
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
