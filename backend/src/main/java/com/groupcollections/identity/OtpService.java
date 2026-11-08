package com.groupcollections.identity;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.security.PhoneCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OtpService {

  private static final Logger log = LoggerFactory.getLogger(OtpService.class);

  public record VerifiedOrganizer(OrganizerUser user, String token, Instant expiresAt) {}

  private record Prepared(java.util.UUID challengeId, String code) {}

  private final PhoneCipher cipher;
  private final OtpChallengeRepository challenges;
  private final UserRepository users;
  private final SessionService sessions;
  private final SmsSender sms;
  private final OtpRateLimiter rateLimiter;
  private final SecureRandom random;
  private final Clock clock;
  private final Duration ttl;
  private final int maxAttempts;
  private final long resendSeconds;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;
  private final TransactionTemplate transactionTemplate;

  public OtpService(
      PhoneCipher cipher,
      OtpChallengeRepository challenges,
      UserRepository users,
      SessionService sessions,
      SmsSender sms,
      OtpRateLimiter rateLimiter,
      SecureRandom random,
      Clock clock,
      MeterRegistry meters,
      AlertPublisher alerts,
      PlatformTransactionManager transactionManager,
      @Value("${collections.identity.otp-ttl:PT5M}") Duration ttl,
      @Value("${collections.identity.otp-max-attempts:5}") int maxAttempts,
      @Value("${collections.identity.otp-resend-seconds:60}") long resendSeconds) {
    this.cipher = cipher;
    this.challenges = challenges;
    this.users = users;
    this.sessions = sessions;
    this.sms = sms;
    this.rateLimiter = rateLimiter;
    this.random = random;
    this.clock = clock;
    this.meters = meters;
    this.alerts = alerts;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.ttl = ttl;
    this.maxAttempts = maxAttempts;
    this.resendSeconds = resendSeconds;
  }

  public void request(String phone, String clientIp) {
    String normalized = PhoneNormalizer.normalize(phone);
    byte[] phoneHash = cipher.hash(normalized);
    rateLimiter.check(clientIp, phoneHash);

    Instant now = Instant.now(clock);
    Prepared prepared = transactionTemplate.execute(status -> prepare(phoneHash, now));
    try {
      sms.send(
          normalized,
          "Your tgthr code is "
              + prepared.code()
              + ". It expires in "
              + ttl.toMinutes()
              + " minutes. Do not share it.");
    } catch (RuntimeException failure) {
      challenges.consume(prepared.challengeId(), Instant.now(clock));
      meters.counter("identity.sms.failed").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.SMS_FAILURE,
              prepared.challengeId().toString(),
              "OTP SMS delivery failed"));
      log.warn("OTP SMS delivery failed");
      if (failure instanceof IdentityException identity) {
        throw identity;
      }
      throw IdentityException.smsUnavailable();
    }
    meters.counter("identity.otp.requested").increment();
    log.info("OTP code requested");
  }

  private Prepared prepare(byte[] phoneHash, Instant now) {
    challenges.lock(phoneHash);
    Optional<OtpChallengeRepository.Challenge> latest = challenges.findLatest(phoneHash);
    if (latest.isPresent() && latest.get().createdAt().plusSeconds(resendSeconds).isAfter(now)) {
      throw IdentityException.resendTooSoon();
    }
    latest
        .filter(challenge -> challenge.active(now))
        .ifPresent(challenge -> challenges.consume(challenge.id(), now));

    String code = String.format("%06d", random.nextInt(1_000_000));
    java.util.UUID id =
        challenges.insert(phoneHash, cipher.hashOtpCode(code), now.plus(ttl), maxAttempts, now);
    return new Prepared(id, code);
  }

  @Transactional(noRollbackFor = IdentityException.class)
  public VerifiedOrganizer verify(
      String phone, String code, String displayName, String legalName, String clientIp) {
    String normalized = PhoneNormalizer.normalize(phone);
    byte[] phoneHash = cipher.hash(normalized);
    rateLimiter.checkVerify(clientIp, phoneHash);
    Instant now = Instant.now(clock);

    OtpChallengeRepository.Challenge challenge =
        challenges.findLatestActive(phoneHash, now).orElseThrow(this::invalidOtp);
    int attempts = challenges.incrementAttempts(challenge.id(), now).orElseThrow(this::invalidOtp);

    if (!MessageDigest.isEqual(challenge.codeHash(), cipher.hashOtpCode(code))) {
      if (attempts >= challenge.maxAttempts()) {
        challenges.consume(challenge.id(), now);
      }
      throw invalidOtp();
    }

    challenges.consume(challenge.id(), now);
    OrganizerUser user =
        users.findOrCreate(
            phoneHash, cipher.encrypt(normalized), clean(displayName), clean(legalName), now);
    SessionService.IssuedSession session = sessions.issue(user.id(), now);
    meters.counter("identity.otp.verified").increment();
    log.info("OTP verified and session issued");
    return new VerifiedOrganizer(user, session.token(), session.expiresAt());
  }

  private IdentityException invalidOtp() {
    meters.counter("identity.otp.failed", "reason", "invalid").increment();
    return IdentityException.invalidOtp();
  }

  private static String clean(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
