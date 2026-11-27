package com.groupcollections.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The deterministic {@code Clock} test seam (T169). Inject it {@code @Primary} into a slice that
 * behaves differently as time passes:
 *
 * <pre>
 * {@literal @}TestConfiguration
 * static class ClockConfiguration {
 *   {@literal @}Bean {@literal @}Primary
 *   Clock testClock() { return CLOCK; }
 * }
 * </pre>
 *
 * <p>Advance it instead of waiting, and instead of rewriting timestamps in the database — a test
 * that does `update ... set expires_at = now() - interval '1 second'` exercises the SQL, not the
 * clock the code reads.
 *
 * <p>Not thread-safe by design: tests mutate it deliberately. Reset it between tests.
 */
public final class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant now) {
    this.now = now;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  public void advance(Duration duration) {
    this.now = this.now.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  /**
   * A fixed clock cannot honour a different zone meaningfully; returning itself is the convention.
   */
  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
