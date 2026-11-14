package com.groupcollections.operator;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Opaque, server-side operator sessions with absolute and idle expiry (ADR 0017). */
@Service
public class OperatorSessionService {

  public static final String COOKIE_NAME = "tgthr_operator_session";
  public static final String COOKIE_PATH = "/operator";

  public record IssuedSession(String token, Instant expiresAt) {}

  private final JdbcTemplate jdbc;
  private final OperatorRepository operators;
  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;
  private final Duration idleTimeout;
  private final MeterRegistry meters;

  public OperatorSessionService(
      JdbcTemplate jdbc,
      OperatorRepository operators,
      SecureRandom random,
      Clock clock,
      MeterRegistry meters,
      @Value("${collections.operator.session-ttl:PT8H}") Duration sessionTtl,
      @Value("${collections.operator.idle-timeout:PT30M}") Duration idleTimeout) {
    this.jdbc = jdbc;
    this.operators = operators;
    this.random = random;
    this.clock = clock;
    this.meters = meters;
    this.sessionTtl = sessionTtl;
    this.idleTimeout = idleTimeout;
  }

  public IssuedSession issue(UUID operatorId, Instant now) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiresAt = now.plus(sessionTtl);
    jdbc.update(
        """
        insert into operator_sessions
            (operator_id, token_hash, created_at, last_seen_at, expires_at)
        values (?, ?, ?, ?, ?)
        """,
        operatorId,
        hash(token),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(expiresAt));
    meters.counter("operator.session.issued").increment();
    return new IssuedSession(token, expiresAt);
  }

  public Optional<OperatorPrincipal> authenticate(String token, Instant now) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Optional<SessionRow> row =
        jdbc
            .query(
                """
                select s.id, s.last_seen_at, o.id as operator_id, o.username
                from operator_sessions s
                join operators o on o.id = s.operator_id
                where s.token_hash = ?
                  and s.revoked_at is null
                  and s.expires_at > ?
                  and o.status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> {
                  OffsetDateTime lastSeen =
                      resultSet.getObject("last_seen_at", OffsetDateTime.class);
                  return new SessionRow(
                      resultSet.getObject("id", UUID.class),
                      resultSet.getObject("operator_id", UUID.class),
                      resultSet.getString("username"),
                      lastSeen.toInstant());
                },
                hash(token),
                Timestamp.from(now))
            .stream()
            .findFirst();
    if (row.isEmpty()) {
      return Optional.empty();
    }
    SessionRow session = row.get();
    if (!session.lastSeen().plus(idleTimeout).isAfter(now)) {
      revoke(token, now);
      meters.counter("operator.session.idle_expired").increment();
      return Optional.empty();
    }
    jdbc.update(
        "update operator_sessions set last_seen_at = ? where id = ?",
        Timestamp.from(now),
        session.id());
    return Optional.of(
        new OperatorPrincipal(
            session.operatorId(), session.username(), operators.roles(session.operatorId())));
  }

  public void revoke(String token, Instant now) {
    if (token == null || token.isBlank()) {
      return;
    }
    int revoked =
        jdbc.update(
            """
            update operator_sessions
            set revoked_at = ?
            where token_hash = ? and revoked_at is null
            """,
            Timestamp.from(now),
            hash(token));
    if (revoked > 0) {
      meters.counter("operator.session.revoked").increment();
    }
  }

  public Duration sessionTtl() {
    return sessionTtl;
  }

  public Duration idleTimeout() {
    return idleTimeout;
  }

  private byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private record SessionRow(UUID id, UUID operatorId, String username, Instant lastSeen) {}
}
