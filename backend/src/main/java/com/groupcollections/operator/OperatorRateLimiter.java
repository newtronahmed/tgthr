package com.groupcollections.operator;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory per-IP abuse control for operator sign-in and MFA (ADR 0017). */
@Component
public class OperatorRateLimiter {

  private static final long WINDOW_SECONDS = 60;

  private record Window(Instant start, AtomicInteger count) {}

  private final Clock clock;
  private final boolean enabled;
  private final int loginPerIpPerMinute;
  private final int mfaPerIpPerMinute;
  private final MeterRegistry meters;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private volatile Instant lastPrune;

  public OperatorRateLimiter(
      Clock clock,
      @Value("${collections.rate-limit.enabled:true}") boolean enabled,
      @Value("${collections.rate-limit.operator-login-per-ip-per-minute:10}")
          int loginPerIpPerMinute,
      @Value("${collections.rate-limit.operator-mfa-per-ip-per-minute:20}") int mfaPerIpPerMinute,
      MeterRegistry meters) {
    this.clock = clock;
    this.enabled = enabled;
    this.loginPerIpPerMinute = loginPerIpPerMinute;
    this.mfaPerIpPerMinute = mfaPerIpPerMinute;
    this.meters = meters;
  }

  public void checkLogin(String clientIp) {
    check("login", clientIp, loginPerIpPerMinute);
  }

  public void checkMfa(String clientIp) {
    check("mfa", clientIp, mfaPerIpPerMinute);
  }

  private void check(String scope, String clientIp, int limit) {
    if (!enabled) {
      return;
    }
    pruneIfNeeded(Instant.now(clock));
    if (!allow("operator:" + scope + ":ip:" + clientIp, limit)) {
      meters.counter("operator.auth.rate_limited", "scope", scope).increment();
      throw OperatorException.rateLimited();
    }
  }

  int windowCount() {
    return windows.size();
  }

  private void pruneIfNeeded(Instant now) {
    Instant last = lastPrune;
    if (last != null && now.isBefore(last.plusSeconds(WINDOW_SECONDS))) {
      return;
    }
    lastPrune = now;
    windows
        .entrySet()
        .removeIf(entry -> !now.isBefore(entry.getValue().start().plusSeconds(WINDOW_SECONDS)));
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
