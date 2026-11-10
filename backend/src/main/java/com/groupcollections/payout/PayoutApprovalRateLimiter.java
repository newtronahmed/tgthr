package com.groupcollections.payout;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory per-IP and per-approver-phone abuse control for the public approval API. */
@Component
public class PayoutApprovalRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(PayoutApprovalRateLimiter.class);
  private static final long WINDOW_SECONDS = 60;

  private record Window(Instant start, AtomicInteger count) {}

  private final Clock clock;
  private final boolean enabled;
  private final int perIpPerMinute;
  private final int perApproverPerMinute;
  private final MeterRegistry meters;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public PayoutApprovalRateLimiter(
      Clock clock,
      @Value("${collections.rate-limit.enabled:true}") boolean enabled,
      @Value("${collections.rate-limit.approval-per-ip-per-minute:20}") int perIpPerMinute,
      @Value("${collections.rate-limit.approval-per-approver-per-minute:5}")
          int perApproverPerMinute,
      MeterRegistry meters) {
    this.clock = clock;
    this.enabled = enabled;
    this.perIpPerMinute = perIpPerMinute;
    this.perApproverPerMinute = perApproverPerMinute;
    this.meters = meters;
  }

  public void checkIp(String clientIp) {
    if (!enabled) {
      return;
    }
    if (!allow("approval:ip:" + clientIp, perIpPerMinute)) {
      reject("ip");
    }
  }

  public void checkApprover(byte[] approverPhoneHash) {
    if (!enabled) {
      return;
    }
    String key = HexFormat.of().formatHex(approverPhoneHash);
    if (!allow("approval:phone:" + key, perApproverPerMinute)) {
      reject("approver");
    }
  }

  void clear() {
    windows.clear();
  }

  private void reject(String scope) {
    meters.counter("payout.approval.rate_limited", "scope", scope).increment();
    log.warn("payout approval rate limited ({})", scope);
    throw PayoutException.rateLimited();
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
