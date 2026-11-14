package com.groupcollections.operator;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password + TOTP sign-in. Password success only creates a short-lived MFA challenge; the operator
 * session is issued only after a fresh TOTP step, which is persisted to reject replay (ADR 0017).
 */
@Service
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorAuthService {

  public record LoginChallenge(String challengeToken, Instant expiresAt) {}

  public record Authenticated(
      OperatorPrincipal principal, OperatorSessionService.IssuedSession session) {}

  private final OperatorRepository operators;
  private final OperatorMfaRepository challenges;
  private final PasswordHasher hasher;
  private final OperatorSecretCipher cipher;
  private final OperatorSessionService sessions;
  private final SecureRandom random;
  private final Clock clock;
  private final MeterRegistry meters;
  private final Duration mfaTtl;
  private final int maxFailedAttempts;
  private final Duration lockout;
  private final String dummyHash;

  public OperatorAuthService(
      OperatorRepository operators,
      OperatorMfaRepository challenges,
      PasswordHasher hasher,
      OperatorSecretCipher cipher,
      OperatorSessionService sessions,
      SecureRandom random,
      Clock clock,
      MeterRegistry meters,
      @Value("${collections.operator.mfa-ttl:PT5M}") Duration mfaTtl,
      @Value("${collections.operator.max-failed-attempts:5}") int maxFailedAttempts,
      @Value("${collections.operator.lockout:PT15M}") Duration lockout) {
    this.operators = operators;
    this.challenges = challenges;
    this.hasher = hasher;
    this.cipher = cipher;
    this.sessions = sessions;
    this.random = random;
    this.clock = clock;
    this.meters = meters;
    this.mfaTtl = mfaTtl;
    this.maxFailedAttempts = maxFailedAttempts;
    this.lockout = lockout;
    this.dummyHash = hasher.hash("timing-equalisation-password");
  }

  @Transactional(noRollbackFor = OperatorException.class)
  public LoginChallenge beginLogin(String username, String password) {
    Instant now = Instant.now(clock);
    Optional<Operator> found = operators.findByUsername(username);
    if (found.isEmpty()) {
      hasher.matches(password, dummyHash);
      audit(null, "LOGIN_FAILED", "invalid credentials", now);
      meters.counter("operator.auth.login", "result", "invalid").increment();
      throw OperatorException.invalidCredentials();
    }
    Operator operator = found.get();
    if (operator.status() != OperatorStatus.ACTIVE
        || (operator.lockedUntil() != null && operator.lockedUntil().isAfter(now))) {
      hasher.matches(password, operator.passwordHash());
      audit(operator.id(), "LOGIN_BLOCKED", "inactive or locked", now);
      meters.counter("operator.auth.login", "result", "blocked").increment();
      throw OperatorException.invalidCredentials();
    }
    if (!hasher.matches(password, operator.passwordHash())) {
      failPassword(operator, now);
      throw OperatorException.invalidCredentials();
    }
    String challengeToken = randomToken();
    Instant expiresAt = now.plus(mfaTtl);
    challenges.consumeOpenForOperator(operator.id(), now);
    challenges.insert(operator.id(), hash(challengeToken), now, expiresAt, maxFailedAttempts);
    audit(operator.id(), "LOGIN_PASSWORD_OK", null, now);
    meters.counter("operator.auth.login", "result", "challenge").increment();
    return new LoginChallenge(challengeToken, expiresAt);
  }

  @Transactional(noRollbackFor = OperatorException.class)
  public Authenticated verifyMfa(String challengeToken, String code) {
    Instant now = Instant.now(clock);
    Optional<OperatorMfaRepository.Challenge> found =
        challenges.findByTokenHash(hash(challengeToken == null ? "" : challengeToken));
    if (found.isEmpty()) {
      audit(null, "MFA_EXPIRED", "challenge not found", now);
      meters.counter("operator.auth.mfa", "result", "expired").increment();
      throw OperatorException.mfaExpired();
    }
    OperatorMfaRepository.Challenge challenge = found.get();
    if (challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)) {
      audit(challenge.operatorId(), "MFA_EXPIRED", "challenge consumed or expired", now);
      meters.counter("operator.auth.mfa", "result", "expired").increment();
      throw OperatorException.mfaExpired();
    }
    Operator operator =
        operators
            .findById(challenge.operatorId())
            .orElseThrow(OperatorException::invalidCredentials);
    if (operator.status() != OperatorStatus.ACTIVE
        || (operator.lockedUntil() != null && operator.lockedUntil().isAfter(now))) {
      audit(operator.id(), "MFA_BLOCKED", "inactive or locked", now);
      meters.counter("operator.auth.mfa", "result", "blocked").increment();
      throw OperatorException.invalidCredentials();
    }
    byte[] secret = Base32.decode(cipher.decrypt(operator.totpSecretCiphertext()));
    OptionalLong matched = Totp.verify(secret, code, now, operator.lastTotpStep());
    if (matched.isEmpty()) {
      failMfa(operator, challenge, now);
      throw OperatorException.invalidCode();
    }
    if (operators.updateLastTotpStep(operator.id(), matched.getAsLong(), now) == 0) {
      audit(operator.id(), "MFA_REPLAY", "step already consumed", now);
      meters.counter("operator.auth.mfa", "result", "replay").increment();
      throw OperatorException.invalidCode();
    }
    if (challenges.consume(challenge.id(), now) == 0) {
      audit(operator.id(), "MFA_RACE", "challenge already consumed", now);
      meters.counter("operator.auth.mfa", "result", "race").increment();
      throw OperatorException.invalidCode();
    }
    operators.resetFailures(operator.id(), now);
    OperatorSessionService.IssuedSession session = sessions.issue(operator.id(), now);
    OperatorPrincipal principal =
        new OperatorPrincipal(operator.id(), operator.username(), operators.roles(operator.id()));
    audit(operator.id(), "MFA_SUCCEEDED", null, now);
    meters.counter("operator.auth.mfa", "result", "success").increment();
    return new Authenticated(principal, session);
  }

  @Transactional
  public void logout(OperatorPrincipal principal, String token) {
    Instant now = Instant.now(clock);
    sessions.revoke(token, now);
    if (principal != null) {
      audit(principal.id(), "LOGGED_OUT", null, now);
    }
  }

  private void failPassword(Operator operator, Instant now) {
    operators.recordFailedAttempt(operator.id(), now, maxFailedAttempts, now.plus(lockout));
    audit(operator.id(), "LOGIN_FAILED", "invalid credentials", now);
    meters.counter("operator.auth.login", "result", "invalid").increment();
  }

  private void failMfa(Operator operator, OperatorMfaRepository.Challenge challenge, Instant now) {
    challenges.incrementAttempts(challenge.id());
    operators.recordFailedAttempt(operator.id(), now, maxFailedAttempts, now.plus(lockout));
    if (challenge.attempts() + 1 >= challenge.maxAttempts()) {
      challenges.consume(challenge.id(), now);
    }
    audit(operator.id(), "MFA_FAILED", "invalid code", now);
    meters.counter("operator.auth.mfa", "result", "invalid").increment();
  }

  private void audit(UUID operatorId, String event, String reason, Instant now) {
    operators.audit(operatorId, event, reason, now);
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }
}
