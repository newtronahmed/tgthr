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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator administration: one-time enrollment, roles, disable/enable and the first-admin
 * bootstrap. Every privileged action is audited; role/disable changes require fresh TOTP.
 */
@Service
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorAdminService {

  private static final String PENDING_PASSWORD = "PENDING_ENROLLMENT";
  private static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{3,64}";

  private final OperatorRepository operators;
  private final OperatorEnrollmentRepository enrollments;
  private final OperatorRecoveryCodeRepository recoveryCodes;
  private final OperatorSecretCipher cipher;
  private final PasswordHasher hasher;
  private final OperatorAuthService auth;
  private final OperatorSessionService sessions;
  private final SecureRandom random;
  private final Clock clock;
  private final MeterRegistry meters;
  private final Duration enrollmentTtl;

  public OperatorAdminService(
      OperatorRepository operators,
      OperatorEnrollmentRepository enrollments,
      OperatorRecoveryCodeRepository recoveryCodes,
      OperatorSecretCipher cipher,
      PasswordHasher hasher,
      OperatorAuthService auth,
      OperatorSessionService sessions,
      SecureRandom random,
      Clock clock,
      MeterRegistry meters,
      @Value("${collections.operator.enrollment-ttl:PT24H}") Duration enrollmentTtl) {
    this.operators = operators;
    this.enrollments = enrollments;
    this.recoveryCodes = recoveryCodes;
    this.cipher = cipher;
    this.hasher = hasher;
    this.auth = auth;
    this.sessions = sessions;
    this.random = random;
    this.clock = clock;
    this.meters = meters;
    this.enrollmentTtl = enrollmentTtl;
  }

  @Transactional
  public OperatorCreated create(
      OperatorPrincipal actor, CreateOperatorRequest request, String totpCode) {
    actor.requireRole(OperatorRole.OPERATOR_ADMIN);
    auth.requireFreshTotp(actor, totpCode);
    Instant now = Instant.now(clock);
    String username = username(request.username());
    if (operators.findByUsername(username).isPresent()) {
      throw OperatorException.conflict("OPERATOR_EXISTS", "That username already exists");
    }
    Provisioned provisioned;
    try {
      provisioned = provision(username, roles(request.roles()), actor.id(), now);
    } catch (DuplicateKeyException failure) {
      throw OperatorException.conflict("OPERATOR_EXISTS", "That username already exists");
    }
    operators.recordAction(
        actor.id(),
        "OPERATOR_CREATED",
        "OPERATOR",
        provisioned.id(),
        null,
        null,
        OperatorStatus.PENDING_ENROLLMENT.name(),
        now);
    meters.counter("operator.admin.created").increment();
    return provisioned.created();
  }

  /** Deploy-time first admin. Returns empty when any operator already exists. */
  @Transactional
  public Optional<OperatorCreated> bootstrapFirstAdmin(String username) {
    operators.lockBootstrap();
    if (operators.count() > 0) {
      return Optional.empty();
    }
    Instant now = Instant.now(clock);
    Provisioned provisioned =
        provision(username(username), Set.of(OperatorRole.OPERATOR_ADMIN), null, now);
    operators.audit(provisioned.id(), "BOOTSTRAP_CREATED", null, now);
    meters.counter("operator.bootstrap.created").increment();
    return Optional.of(provisioned.created());
  }

  @Transactional
  public OperatorEnrollmentDetails enroll(String token, String password) {
    Instant now = Instant.now(clock);
    OperatorEnrollmentRepository.Enrollment enrollment = validEnrollment(token, now);
    if (enrollment.passwordSetAt() != null) {
      throw OperatorException.conflict(
          "ENROLLMENT_ALREADY_STARTED", "The enrollment password is already set");
    }
    Operator operator =
        operators
            .findById(enrollment.operatorId())
            .orElseThrow(OperatorException::enrollmentInvalid);
    if (operator.status() != OperatorStatus.PENDING_ENROLLMENT) {
      throw OperatorException.conflict("ENROLLMENT_INVALID", "The account is not enrolling");
    }
    String passwordHash;
    try {
      passwordHash = hasher.hash(password);
    } catch (IllegalArgumentException failure) {
      throw OperatorException.invalid("INVALID_PASSWORD", failure.getMessage());
    }
    if (enrollments.markPasswordSet(enrollment.id(), now) == 0) {
      throw OperatorException.enrollmentInvalid();
    }
    operators.updatePassword(operator.id(), passwordHash, now);
    RecoveryCodes.Generated generated = RecoveryCodes.generate(random);
    recoveryCodes.replace(operator.id(), generated.hashes());
    operators.audit(operator.id(), "ENROLLMENT_STARTED", null, now);
    String secret = cipher.decrypt(operator.totpSecretCiphertext());
    return new OperatorEnrollmentDetails(
        secret, otpauthUri(operator.username(), secret), generated.plaintext());
  }

  @Transactional
  public void confirmEnrollment(String token, String code) {
    Instant now = Instant.now(clock);
    OperatorEnrollmentRepository.Enrollment enrollment = validEnrollment(token, now);
    if (enrollment.passwordSetAt() == null) {
      throw OperatorException.conflict(
          "ENROLLMENT_NOT_STARTED", "Set a password before confirming enrollment");
    }
    Operator operator =
        operators
            .findById(enrollment.operatorId())
            .orElseThrow(OperatorException::enrollmentInvalid);
    if (operator.status() != OperatorStatus.PENDING_ENROLLMENT) {
      throw OperatorException.conflict("ENROLLMENT_INVALID", "The account is not enrolling");
    }
    byte[] secret = Base32.decode(cipher.decrypt(operator.totpSecretCiphertext()));
    OptionalLong matched = Totp.verify(secret, code, now, -1);
    if (matched.isEmpty()) {
      throw OperatorException.invalidCode();
    }
    operators.updateLastTotpStep(operator.id(), matched.getAsLong(), now);
    operators.setStatus(operator.id(), OperatorStatus.ACTIVE, now);
    if (enrollments.consume(enrollment.id(), now) == 0) {
      throw OperatorException.conflict("ENROLLMENT_INVALID", "The enrollment was already used");
    }
    operators.audit(operator.id(), "OPERATOR_ENROLLED", null, now);
    meters.counter("operator.enrollment.confirmed").increment();
  }

  @Transactional
  public OperatorAdminView changeRoles(
      OperatorPrincipal actor, UUID targetId, ChangeRolesRequest request, String totpCode) {
    actor.requireRole(OperatorRole.OPERATOR_ADMIN);
    auth.requireFreshTotp(actor, totpCode);
    Instant now = Instant.now(clock);
    Operator target = operators.findById(targetId).orElseThrow(OperatorException::notFound);
    Set<OperatorRole> requested = roles(request.roles());
    Set<OperatorRole> current = operators.roles(targetId);
    if (current.contains(OperatorRole.OPERATOR_ADMIN)
        && !requested.contains(OperatorRole.OPERATOR_ADMIN)
        && operators.countActiveAdmins() <= 1) {
      throw OperatorException.conflict(
          "LAST_ADMIN", "The last active operator admin cannot lose the admin role");
    }
    for (OperatorRole role : requested) {
      if (!current.contains(role)) {
        operators.grantRole(targetId, role, actor.id(), now);
      }
    }
    for (OperatorRole role : current) {
      if (!requested.contains(role)) {
        operators.removeRole(targetId, role);
      }
    }
    operators.recordAction(
        actor.id(),
        "ROLES_CHANGED",
        "OPERATOR",
        targetId,
        null,
        join(current),
        join(requested),
        now);
    return view(target);
  }

  @Transactional
  public OperatorAdminView disable(OperatorPrincipal actor, UUID targetId, String totpCode) {
    actor.requireRole(OperatorRole.OPERATOR_ADMIN);
    auth.requireFreshTotp(actor, totpCode);
    if (actor.id().equals(targetId)) {
      throw OperatorException.conflict("SELF_DISABLE", "An operator cannot disable themselves");
    }
    Instant now = Instant.now(clock);
    Operator target = operators.findById(targetId).orElseThrow(OperatorException::notFound);
    Set<OperatorRole> roles = operators.roles(targetId);
    if (target.status() == OperatorStatus.ACTIVE
        && roles.contains(OperatorRole.OPERATOR_ADMIN)
        && operators.countActiveAdmins() <= 1) {
      throw OperatorException.conflict(
          "LAST_ADMIN", "The last active operator admin cannot be disabled");
    }
    operators.setStatus(targetId, OperatorStatus.DISABLED, now);
    sessions.revokeAllForOperator(targetId, now);
    operators.recordAction(
        actor.id(),
        "OPERATOR_DISABLED",
        "OPERATOR",
        targetId,
        null,
        OperatorStatus.ACTIVE.name(),
        OperatorStatus.DISABLED.name(),
        now);
    meters.counter("operator.admin.disabled").increment();
    return view(operators.findById(targetId).orElseThrow(OperatorException::notFound));
  }

  @Transactional
  public OperatorAdminView enable(OperatorPrincipal actor, UUID targetId, String totpCode) {
    actor.requireRole(OperatorRole.OPERATOR_ADMIN);
    auth.requireFreshTotp(actor, totpCode);
    Instant now = Instant.now(clock);
    Operator target = operators.findById(targetId).orElseThrow(OperatorException::notFound);
    operators.setStatus(targetId, OperatorStatus.ACTIVE, now);
    operators.recordAction(
        actor.id(),
        "OPERATOR_ENABLED",
        "OPERATOR",
        targetId,
        null,
        OperatorStatus.DISABLED.name(),
        OperatorStatus.ACTIVE.name(),
        now);
    return view(operators.findById(targetId).orElseThrow(OperatorException::notFound));
  }

  public List<OperatorAdminView> list(OperatorPrincipal actor) {
    actor.requireRole(OperatorRole.OPERATOR_ADMIN);
    return operators.findAll().stream().map(this::view).toList();
  }

  public OperatorAdminView get(OperatorPrincipal actor, UUID targetId) {
    actor.requireRole(OperatorRole.OPERATOR_ADMIN);
    return view(operators.findById(targetId).orElseThrow(OperatorException::notFound));
  }

  private OperatorEnrollmentRepository.Enrollment validEnrollment(String token, Instant now) {
    OperatorEnrollmentRepository.Enrollment enrollment =
        enrollments
            .findByTokenHash(hash(token == null ? "" : token))
            .orElseThrow(OperatorException::enrollmentInvalid);
    if (enrollment.consumedAt() != null || !enrollment.expiresAt().isAfter(now)) {
      throw OperatorException.enrollmentInvalid();
    }
    return enrollment;
  }

  private Provisioned provision(
      String username, Set<OperatorRole> roles, UUID grantedBy, Instant now) {
    UUID id = UUID.randomUUID();
    byte[] secret = new byte[20];
    random.nextBytes(secret);
    String encodedSecret = Base32.encode(secret);
    operators.insert(
        id,
        username,
        PENDING_PASSWORD,
        cipher.encrypt(encodedSecret),
        OperatorStatus.PENDING_ENROLLMENT,
        now);
    for (OperatorRole role : roles) {
      operators.grantRole(id, role, grantedBy, now);
    }
    String token = randomToken();
    Instant expiresAt = now.plus(enrollmentTtl);
    enrollments.insert(id, hash(token), now, expiresAt);
    return new Provisioned(id, username, token, expiresAt);
  }

  private OperatorAdminView view(Operator operator) {
    return new OperatorAdminView(
        operator.id(),
        operator.username(),
        operator.status(),
        operators.roles(operator.id()).stream().map(Enum::name).sorted().toList());
  }

  private static String username(String value) {
    String username = value == null ? "" : value.trim();
    if (!username.matches(USERNAME_PATTERN)) {
      throw OperatorException.invalid(
          "INVALID_USERNAME", "Use 3-64 letters, digits, dots, underscores or hyphens");
    }
    return username;
  }

  private static Set<OperatorRole> roles(List<String> values) {
    if (values == null || values.isEmpty()) {
      throw OperatorException.invalid("INVALID_ROLE", "At least one role is required");
    }
    Set<OperatorRole> roles = EnumSet.noneOf(OperatorRole.class);
    for (String value : values) {
      try {
        roles.add(OperatorRole.valueOf(value));
      } catch (IllegalArgumentException failure) {
        throw OperatorException.invalid("INVALID_ROLE", "Unknown role: " + value);
      }
    }
    return roles;
  }

  private static String join(Set<OperatorRole> roles) {
    return roles.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse("");
  }

  private static String otpauthUri(String username, String secret) {
    return "otpauth://totp/GroupCollections:"
        + username
        + "?secret="
        + secret
        + "&issuer=GroupCollections&algorithm=SHA1&digits=6&period=30";
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

  private record Provisioned(UUID id, String username, String token, Instant expiresAt) {
    private OperatorCreated created() {
      return new OperatorCreated(id, username, token, expiresAt);
    }
  }
}
