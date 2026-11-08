package com.groupcollections.identity;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

  public static final String COOKIE_NAME = "tgthr_session";

  public record IssuedSession(String token, Instant expiresAt) {}

  private final JdbcTemplate jdbc;
  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;
  private final MeterRegistry meters;

  public SessionService(
      JdbcTemplate jdbc,
      SecureRandom random,
      Clock clock,
      MeterRegistry meters,
      @Value("${collections.identity.session-ttl:P30D}") Duration sessionTtl) {
    this.jdbc = jdbc;
    this.random = random;
    this.clock = clock;
    this.sessionTtl = sessionTtl;
    this.meters = meters;
  }

  public IssuedSession issue(UUID userId, Instant now) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiresAt = now.plus(sessionTtl);
    jdbc.update(
        """
        insert into user_sessions (user_id, token_hash, created_at, expires_at)
        values (?, ?, ?, ?)
        """,
        userId,
        hash(token),
        Timestamp.from(now),
        Timestamp.from(expiresAt));
    meters.counter("identity.session.issued").increment();
    return new IssuedSession(token, expiresAt);
  }

  public Optional<OrganizerUser> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return jdbc
        .query(
            """
            select u.id, u.display_name, u.legal_name, u.created_at
            from user_sessions s
            join users u on u.id = s.user_id
            where s.token_hash = ?
              and s.revoked_at is null
              and s.expires_at > ?
            """,
            (resultSet, rowNumber) ->
                new OrganizerUser(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("display_name"),
                    resultSet.getString("legal_name"),
                    resultSet.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
            hash(token),
            Timestamp.from(Instant.now(clock)))
        .stream()
        .findFirst();
  }

  public void revoke(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    int revoked =
        jdbc.update(
            "update user_sessions set revoked_at = ? where token_hash = ? and revoked_at is null",
            Timestamp.from(Instant.now(clock)),
            hash(token));
    if (revoked > 0) {
      meters.counter("identity.session.revoked").increment();
    }
  }

  public Duration sessionTtl() {
    return sessionTtl;
  }

  private byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }
}
