package com.groupcollections.goal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory per-organizer goal creation limiter; abuse control, not idempotency. */
@Component
public class GoalRateLimiter {

  private static final long WINDOW_SECONDS = 60;

  private record Window(Instant start, AtomicInteger count) {}

  private final Clock clock;
  private final boolean enabled;
  private final int perUserPerMinute;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public GoalRateLimiter(
      Clock clock,
      @Value("${collections.rate-limit.enabled:true}") boolean enabled,
      @Value("${collections.rate-limit.goal-per-user-per-minute:10}") int perUserPerMinute) {
    this.clock = clock;
    this.enabled = enabled;
    this.perUserPerMinute = perUserPerMinute;
  }

  public void check(UUID userId) {
    if (!enabled) {
      return;
    }
    if (!allow("goal:user:" + userId, perUserPerMinute)) {
      throw OrganizerGoalException.rateLimited();
    }
  }

  private boolean allow(String key, int limit) {
    Instant now = Instant.now(clock);
    Window window =
        windows.compute(
            key,
            (ignored, existing) ->
                existing == null || !now.isBefore(existing.start().plusSeconds(WINDOW_SECONDS))
                    ? new Window(now, new AtomicInteger(0))
                    : existing);
    return window.count().incrementAndGet() <= limit;
  }
}
