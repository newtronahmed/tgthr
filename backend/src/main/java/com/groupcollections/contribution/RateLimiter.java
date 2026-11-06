package com.groupcollections.contribution;

import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window limiter per client IP and per payer phone. This is abuse control, not an
 * idempotency mechanism; a shared store would need an ADR.
 */
@Component
public class RateLimiter {

  private static final long WINDOW_SECONDS = 60;

  private record Window(Instant start, AtomicInteger count) {}

  private final Clock clock;
  private final boolean enabled;
  private final int perIpPerMinute;
  private final int perPhonePerMinute;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public RateLimiter(
      Clock clock,
      @Value("${collections.rate-limit.enabled:true}") boolean enabled,
      @Value("${collections.rate-limit.per-ip-per-minute:30}") int perIpPerMinute,
      @Value("${collections.rate-limit.per-phone-per-minute:5}") int perPhonePerMinute) {
    this.clock = clock;
    this.enabled = enabled;
    this.perIpPerMinute = perIpPerMinute;
    this.perPhonePerMinute = perPhonePerMinute;
  }

  public void check(String clientIp, byte[] phoneHash) {
    if (!enabled) {
      return;
    }
    String phoneKey = HexFormat.of().formatHex(phoneHash);
    if (!allow("ip:" + clientIp, perIpPerMinute)
        || !allow("phone:" + phoneKey, perPhonePerMinute)) {
      throw new RateLimitExceededException();
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
