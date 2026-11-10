package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PayoutApprovalRateLimiterTest {

  private static final byte[] KEY = "approver-a".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  @Test
  void limitsPerIpIndependentlyOfTheApproverBucket() {
    PayoutApprovalRateLimiter limiter =
        new PayoutApprovalRateLimiter(clock(Instant.now()), true, 1, 10, new SimpleMeterRegistry());

    assertThatCode(() -> limiter.checkIp("1.2.3.4")).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.checkIp("1.2.3.4"))
        .isInstanceOf(PayoutException.class)
        .hasMessageContaining("Too many approval requests");
    assertThatCode(() -> limiter.checkIp("5.6.7.8")).doesNotThrowAnyException();
  }

  @Test
  void limitsPerApproverAcrossIps() {
    PayoutApprovalRateLimiter limiter =
        new PayoutApprovalRateLimiter(clock(Instant.now()), true, 10, 1, new SimpleMeterRegistry());

    assertThatCode(() -> limiter.checkApprover(KEY)).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.checkApprover(KEY))
        .isInstanceOf(PayoutException.class)
        .hasMessageContaining("Too many approval requests");
    assertThatCode(
            () ->
                limiter.checkApprover(
                    "approver-b".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .doesNotThrowAnyException();
  }

  @Test
  void windowResetsAfterOneMinute() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    PayoutApprovalRateLimiter limiter =
        new PayoutApprovalRateLimiter(mutableClock(now), true, 1, 1, new SimpleMeterRegistry());

    assertThatCode(() -> limiter.checkIp("1.2.3.4")).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.checkIp("1.2.3.4")).isInstanceOf(PayoutException.class);
    now.set(now.get().plusSeconds(61));
    assertThatCode(() -> limiter.checkIp("1.2.3.4")).doesNotThrowAnyException();
  }

  @Test
  void disabledLimiterAllowsEverything() {
    PayoutApprovalRateLimiter limiter =
        new PayoutApprovalRateLimiter(clock(Instant.now()), false, 1, 1, new SimpleMeterRegistry());

    assertThatCode(() -> limiter.checkIp("1.2.3.4")).doesNotThrowAnyException();
    assertThatCode(() -> limiter.checkIp("1.2.3.4")).doesNotThrowAnyException();
    assertThatCode(() -> limiter.checkApprover(KEY)).doesNotThrowAnyException();
    assertThatCode(() -> limiter.checkApprover(KEY)).doesNotThrowAnyException();
  }

  private static Clock clock(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  private static Clock mutableClock(AtomicReference<Instant> now) {
    return new Clock() {
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
        return now.get();
      }
    };
  }
}
