package com.groupcollections.goal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoalRateLimiterTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void limitsGoalCreationPerOrganizer() {
    GoalRateLimiter limiter = new GoalRateLimiter(clock, true, 2);
    UUID user = UUID.randomUUID();

    assertThatCode(() -> limiter.check(user)).doesNotThrowAnyException();
    assertThatCode(() -> limiter.check(user)).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.check(user))
        .isInstanceOf(OrganizerGoalException.class)
        .hasMessageContaining("Too many goal attempts");
    assertThatCode(() -> limiter.check(UUID.randomUUID())).doesNotThrowAnyException();
  }

  @Test
  void disabledLimiterDoesNotThrow() {
    GoalRateLimiter limiter = new GoalRateLimiter(clock, false, 1);
    UUID user = UUID.randomUUID();
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThatCode(() -> limiter.check(user)).doesNotThrowAnyException();
    }
  }
}
