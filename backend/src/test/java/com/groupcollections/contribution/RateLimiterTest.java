package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private static final byte[] PHONE = {1, 2, 3};

  @Test
  void blocksAfterThePhoneLimitAndResetsAfterTheWindow() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    RateLimiter limiter = new RateLimiter(clock, true, 100, 2);

    limiter.check("1.1.1.1", PHONE);
    limiter.check("1.1.1.1", PHONE);
    assertThatThrownBy(() -> limiter.check("1.1.1.1", PHONE))
        .isInstanceOf(RateLimitExceededException.class);

    clock.advance(Duration.ofSeconds(61));
    limiter.check("1.1.1.1", PHONE);
  }

  @Test
  void limitsPerIpAcrossDifferentPhones() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    RateLimiter limiter = new RateLimiter(clock, true, 1, 100);

    limiter.check("1.1.1.1", new byte[] {1});
    assertThatThrownBy(() -> limiter.check("1.1.1.1", new byte[] {2}))
        .isInstanceOf(RateLimitExceededException.class);
    limiter.check("2.2.2.2", new byte[] {2});
  }

  @Test
  void theDisabledLimiterAllowsEverything() {
    RateLimiter limiter = new RateLimiter(Clock.systemUTC(), false, 0, 0);

    limiter.check("1.1.1.1", PHONE);
    limiter.check("1.1.1.1", PHONE);
  }
}
