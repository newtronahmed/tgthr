package com.groupcollections.payout;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Payout-owned report rate limit; separate from contribution payment limits. */
@Component
public class PayoutReportRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(PayoutReportRateLimiter.class);
  private static final long WINDOW_SECONDS = 60;

  private record Window(Instant start, AtomicInteger count) {}

  private final Clock clock;
  private final boolean enabled;
  private final int perIpPerMinute;
  private final MeterRegistry meters;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public PayoutReportRateLimiter(
      Clock clock,
      @Value("${collections.rate-limit.enabled:true}") boolean enabled,
      @Value("${collections.rate-limit.payout-report-per-ip-per-minute:10}") int perIpPerMinute,
      MeterRegistry meters) {
    this.clock = clock;
    this.enabled = enabled;
    this.perIpPerMinute = perIpPerMinute;
    this.meters = meters;
  }

  public void check(String clientIp) {
    if (!enabled) {
      return;
    }
    Instant now = Instant.now(clock);
    String key = "payout-report:ip:" + clientIp;
    Window window =
        windows.compute(
            key,
            (ignored, existing) ->
                existing == null || !now.isBefore(existing.start().plusSeconds(WINDOW_SECONDS))
                    ? new Window(now, new AtomicInteger(0))
                    : existing);
    if (window.count().incrementAndGet() > perIpPerMinute) {
      meters.counter("payout.report.rate_limited").increment();
      log.warn("payout report rate limited");
      throw PayoutException.reportRateLimited();
    }
  }
}
