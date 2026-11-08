package com.groupcollections.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** In-memory OTP abuse control, separate from database-backed single-use enforcement. */
@Component
public class OtpRateLimiter {

  private static final long WINDOW_SECONDS = 60;

  private record Window(Instant start, AtomicInteger count) {}

  private final Clock clock;
  private final boolean enabled;
  private final int perPhonePerMinute;
  private final int perIpPerMinute;
  private final int verifyPerPhonePerMinute;
  private final int verifyPerIpPerMinute;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public OtpRateLimiter(
      Clock clock,
      @Value("${collections.rate-limit.enabled:true}") boolean enabled,
      @Value("${collections.rate-limit.otp-per-phone-per-minute:3}") int perPhonePerMinute,
      @Value("${collections.rate-limit.otp-per-ip-per-minute:10}") int perIpPerMinute,
      @Value("${collections.rate-limit.otp-verify-per-phone-per-minute:10}")
          int verifyPerPhonePerMinute,
      @Value("${collections.rate-limit.otp-verify-per-ip-per-minute:20}")
          int verifyPerIpPerMinute) {
    this.clock = clock;
    this.enabled = enabled;
    this.perPhonePerMinute = perPhonePerMinute;
    this.perIpPerMinute = perIpPerMinute;
    this.verifyPerPhonePerMinute = verifyPerPhonePerMinute;
    this.verifyPerIpPerMinute = verifyPerIpPerMinute;
  }

  public void check(String clientIp, byte[] phoneHash) {
    if (!enabled) {
      return;
    }
    String phoneKey = HexFormat.of().formatHex(phoneHash);
    if (!allow("otp:ip:" + clientIp, perIpPerMinute)
        || !allow("otp:phone:" + phoneKey, perPhonePerMinute)) {
      throw IdentityException.rateLimited();
    }
  }

  public void checkVerify(String clientIp, byte[] phoneHash) {
    if (!enabled) {
      return;
    }
    String phoneKey = HexFormat.of().formatHex(phoneHash);
    if (!allow("otp:verify:ip:" + clientIp, verifyPerIpPerMinute)
        || !allow("otp:verify:phone:" + phoneKey, verifyPerPhonePerMinute)) {
      throw IdentityException.rateLimited();
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
