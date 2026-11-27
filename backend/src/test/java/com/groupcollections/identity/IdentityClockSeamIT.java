package com.groupcollections.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * T169: expiry is driven by the injected {@code Clock}, not by wall time and not by rewriting rows.
 *
 * <p>These tests advance the clock and expect the code to reach the same conclusion on its own. A
 * test that instead runs {@code update ... set expires_at = now() - interval '1 second'} proves the
 * SQL works; it does not prove the service reads the clock it was given.
 */
class IdentityClockSeamIT extends ApiIntegrationTest {

  private static final Duration SESSION_TTL = Duration.ofDays(30);

  static final MutableClock CLOCK = new MutableClock(Instant.now());

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired private SessionService sessions;

  @BeforeEach
  void resetClock() {
    CLOCK.set(Instant.now());
  }

  @Test
  void aSessionExpiresWhenTheClockPassesItsTtlAndIsStillValidJustBefore() {
    UUID userId = insertUser();
    Instant issuedAt = Instant.now(CLOCK);
    SessionService.IssuedSession session = sessions.issue(userId, issuedAt);

    // One second before expiry the session is valid.
    CLOCK.set(session.expiresAt().minusSeconds(1));
    assertThat(sessions.authenticate(session.token())).isPresent();

    // One second after, it is not — decided by the clock the service was given.
    CLOCK.set(session.expiresAt().plusSeconds(1));
    assertThat(sessions.authenticate(session.token())).isEmpty();
  }

  @Test
  void theIssuedExpiryIsTheClockPlusTheConfiguredTtl() {
    UUID userId = insertUser();
    Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
    CLOCK.set(issuedAt);

    SessionService.IssuedSession session = sessions.issue(userId, Instant.now(CLOCK));

    assertThat(session.expiresAt()).isEqualTo(issuedAt.plus(SESSION_TTL));
    assertThat(sessions.sessionTtl()).isEqualTo(SESSION_TTL);
  }

  private UUID insertUser() {
    return jdbc.queryForObject(
        """
        insert into users (phone_ciphertext, phone_hash, display_name)
        values (?, ?, 'Clock Test')
        returning id
        """,
        UUID.class,
        randomBytes(),
        randomBytes());
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
