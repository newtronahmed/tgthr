package com.groupcollections.goal;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.identity.IdentityException;
import com.groupcollections.identity.OrganizerUser;
import com.groupcollections.identity.PhoneNormalizer;
import com.groupcollections.identity.UserRepository;
import com.groupcollections.security.PhoneCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrganizerGoalService {

  private static final Logger log = LoggerFactory.getLogger(OrganizerGoalService.class);
  private static final long DEFAULT_THRESHOLD_PESEWAS = 50_000;
  private static final int DEFAULT_HOLD_WINDOW_MINUTES = 60;
  private static final int MAX_SLUG_ATTEMPTS = 3;

  private final OrganizerGoalRepository goals;
  private final UserRepository users;
  private final PhoneCipher phones;
  private final AccountResolver resolver;
  private final SlugGenerator slugs;
  private final Clock clock;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;
  private final GoalRateLimiter rateLimiter;
  private final TransactionTemplate transactionTemplate;

  public OrganizerGoalService(
      OrganizerGoalRepository goals,
      UserRepository users,
      PhoneCipher phones,
      AccountResolver resolver,
      SlugGenerator slugs,
      Clock clock,
      MeterRegistry meters,
      AlertPublisher alerts,
      GoalRateLimiter rateLimiter,
      PlatformTransactionManager transactionManager) {
    this.goals = goals;
    this.users = users;
    this.phones = phones;
    this.resolver = resolver;
    this.slugs = slugs;
    this.clock = clock;
    this.meters = meters;
    this.alerts = alerts;
    this.rateLimiter = rateLimiter;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public OrganizerGoal create(
      OrganizerUser user, String idempotencyKey, CreateGoalRequest request) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() < 8
        || idempotencyKey.length() > 64) {
      throw OrganizerGoalException.invalid("Idempotency-Key must be 8 to 64 characters");
    }
    Optional<OrganizerGoal> original =
        goals.findByCreatorAndIdempotencyKey(user.id(), idempotencyKey);
    if (original.isPresent()) {
      return original.get();
    }

    try {
      rateLimiter.check(user.id());
    } catch (OrganizerGoalException failure) {
      meters.counter("goals.rate_limited").increment();
      log.warn("goal creation rate limited");
      throw failure;
    }
    Instant now = Instant.now(clock);
    validate(request, now);
    String legalName = clean(user.legalName());
    if (request.destinationType() == DestinationType.ADMIN_MOMO && legalName == null) {
      destinationFailed("legal_name", user.id());
      throw OrganizerGoalException.legalNameRequired();
    }

    String normalizedDestination = PhoneNormalizer.normalize(request.destinationPhone());
    String accountName = resolve(normalizedDestination, request.network(), user.id());
    if (accountName == null || accountName.isBlank()) {
      destinationFailed("unresolved", user.id());
      throw OrganizerGoalException.destinationUnverified();
    }
    if (request.destinationType() == DestinationType.ADMIN_MOMO
        && !NameMatcher.matches(accountName, legalName)) {
      destinationFailed("name_mismatch", user.id());
      throw OrganizerGoalException.destinationUnverified();
    }
    if (request.destinationType() == DestinationType.ADMIN_MOMO && !ownsPhone(user, request)) {
      destinationFailed("not_owner", user.id());
      throw OrganizerGoalException.destinationUnverified();
    }

    ApproverIdentity approver = resolveApprover(user, request);
    long minimum =
        request.targetType() == TargetType.OPEN
            ? request.minimumAmountPesewas()
            : request.minimumAmountPesewas() == null ? 1_000 : request.minimumAmountPesewas();
    long threshold =
        request.secondApproverThresholdPesewas() == null
            ? DEFAULT_THRESHOLD_PESEWAS
            : request.secondApproverThresholdPesewas();
    int holdWindow =
        request.holdWindowMinutes() == null
            ? DEFAULT_HOLD_WINDOW_MINUTES
            : request.holdWindowMinutes();
    if (holdWindow < 1) {
      throw OrganizerGoalException.holdRequired();
    }
    SurplusRule surplus =
        request.surplusRule() == null ? SurplusRule.PAY_BENEFICIARY : request.surplusRule();

    for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
      Optional<UUID> inserted =
          transactionTemplate.execute(
              status -> {
                Optional<UUID> id =
                    goals.insert(
                        slugs.next(),
                        request,
                        minimum,
                        threshold,
                        holdWindow,
                        surplus,
                        user.id(),
                        phones.encrypt(normalizedDestination),
                        phones.hash(normalizedDestination),
                        accountName,
                        approver == null ? null : approver.name(),
                        approver == null ? null : approver.ciphertext(),
                        approver == null ? null : approver.hash(),
                        approver == null ? null : approver.setAt(),
                        idempotencyKey,
                        now);
                id.ifPresent(
                    goalId -> goals.appendEvent(goalId, "CREATED", null, "OPEN", user.id(), now));
                return id;
              });
      if (inserted != null && inserted.isPresent()) {
        meters.counter("goals.created").increment();
        log.info("goal created {}", inserted.get());
        return goals
            .findByIdAndCreator(inserted.get(), user.id())
            .orElseThrow(() -> new IllegalStateException("created goal is missing"));
      }
      Optional<OrganizerGoal> concurrent =
          goals.findByCreatorAndIdempotencyKey(user.id(), idempotencyKey);
      if (concurrent.isPresent()) {
        return concurrent.get();
      }
    }
    throw new IllegalStateException("could not allocate a goal slug");
  }

  @Transactional(readOnly = true)
  public List<OrganizerGoal> list(OrganizerUser user) {
    return goals.listByCreator(user.id());
  }

  @Transactional(readOnly = true)
  public OrganizerGoal get(OrganizerUser user, UUID goalId) {
    return goals
        .findByIdAndCreator(goalId, user.id())
        .orElseThrow(() -> new GoalNotFoundException(goalId.toString()));
  }

  @Transactional
  public OrganizerGoal close(OrganizerUser user, UUID goalId) {
    OrganizerGoal goal = get(user, goalId);
    if (goal.status() == GoalStatus.CLOSED) {
      return goal;
    }
    if (goal.status() != GoalStatus.OPEN) {
      throw OrganizerGoalException.notClosable();
    }
    Instant now = Instant.now(clock);
    if (goals.close(goalId, user.id(), now) == 1) {
      goals.appendEvent(goalId, "CLOSED", "OPEN", "CLOSED", user.id(), now);
      meters.counter("goals.closed").increment();
      log.info("goal closed {}", goalId);
    }
    OrganizerGoal after = get(user, goalId);
    if (after.status() != GoalStatus.CLOSED) {
      throw OrganizerGoalException.notClosable();
    }
    return after;
  }

  @Transactional
  public OrganizerGoal updatePayoutSettings(
      OrganizerUser user, UUID goalId, PayoutSettingsRequest request) {
    if (!goals.lockByIdAndCreator(goalId, user.id())) {
      throw new GoalNotFoundException(goalId.toString());
    }
    OrganizerGoal goal = get(user, goalId);
    if (goals.hasPayouts(goalId)) {
      throw OrganizerGoalException.payoutSettingsLocked();
    }
    if (request.secondApproverName() == null
        && request.secondApproverPhone() == null
        && request.holdWindowMinutes() == null) {
      throw OrganizerGoalException.invalid("provide at least one payout setting");
    }
    if (request.holdWindowMinutes() != null && request.holdWindowMinutes() < 1) {
      throw OrganizerGoalException.holdRequired();
    }

    String approverName = null;
    byte[] approverCiphertext = null;
    byte[] approverHash = null;
    Instant approverSetAt = null;
    if (request.secondApproverName() != null || request.secondApproverPhone() != null) {
      String name = clean(request.secondApproverName());
      String rawPhone = clean(request.secondApproverPhone());
      if (name == null || rawPhone == null) {
        throw OrganizerGoalException.invalid(
            "secondApproverName and secondApproverPhone must be provided together");
      }
      String normalized;
      try {
        normalized = PhoneNormalizer.normalize(rawPhone);
      } catch (IdentityException failure) {
        throw OrganizerGoalException.invalid(
            "secondApproverPhone must be a Ghanaian mobile number");
      }
      byte[] hash = phones.hash(normalized);
      if (sameAsOrganizer(user, hash)) {
        throw OrganizerGoalException.approverMustDiffer();
      }
      approverName = name;
      approverCiphertext = phones.encrypt(normalized);
      approverHash = hash;
      approverSetAt = Instant.now(clock);
    } else if (goal.secondApprover() != SecondApprover.OFF && goal.secondApproverName() == null) {
      throw OrganizerGoalException.approverRequired();
    }

    Instant now = Instant.now(clock);
    goals.updatePayoutSettings(
        goalId,
        user.id(),
        approverName,
        approverCiphertext,
        approverHash,
        approverSetAt,
        request.holdWindowMinutes(),
        now);
    goals.appendEvent(
        goalId,
        "PAYOUT_SETTINGS_UPDATED",
        goal.status().name(),
        goal.status().name(),
        user.id(),
        now);
    return get(user, goalId);
  }

  private ApproverIdentity resolveApprover(OrganizerUser user, CreateGoalRequest request) {
    if (request.secondApproverName() == null && request.secondApproverPhone() == null) {
      return null;
    }
    String name = clean(request.secondApproverName());
    String rawPhone = clean(request.secondApproverPhone());
    if (name == null || rawPhone == null) {
      throw OrganizerGoalException.invalid(
          "secondApproverName and secondApproverPhone must be provided together");
    }
    String normalized;
    try {
      normalized = PhoneNormalizer.normalize(rawPhone);
    } catch (IdentityException failure) {
      throw OrganizerGoalException.invalid("secondApproverPhone must be a Ghanaian mobile number");
    }
    byte[] hash = phones.hash(normalized);
    if (sameAsOrganizer(user, hash)) {
      throw OrganizerGoalException.approverMustDiffer();
    }
    return new ApproverIdentity(name, phones.encrypt(normalized), hash, Instant.now(clock));
  }

  private boolean sameAsOrganizer(OrganizerUser user, byte[] approverHash) {
    return users
        .findPhoneHash(user.id())
        .map(hash -> MessageDigest.isEqual(hash, approverHash))
        .orElse(false);
  }

  private String resolve(String phone, com.groupcollections.payments.Network network, UUID userId) {
    try {
      return resolver.resolve(phone, network);
    } catch (OrganizerGoalException failure) {
      destinationFailed("unavailable", userId);
      log.warn("destination resolution unavailable");
      throw failure;
    } catch (RuntimeException failure) {
      destinationFailed("unavailable", userId);
      log.warn("destination resolution failed", failure);
      throw OrganizerGoalException.destinationUnavailable();
    }
  }

  private boolean ownsPhone(OrganizerUser user, CreateGoalRequest request) {
    String normalized = PhoneNormalizer.normalize(request.destinationPhone());
    return users
        .findPhoneHash(user.id())
        .map(hash -> MessageDigest.isEqual(hash, phones.hash(normalized)))
        .orElse(false);
  }

  private void destinationFailed(String reason, UUID userId) {
    meters.counter("destination.resolve.failed", "reason", reason).increment();
    Alert.AlertType type =
        "unavailable".equals(reason)
            ? Alert.AlertType.DESTINATION_UNAVAILABLE
            : Alert.AlertType.DESTINATION_UNVERIFIED;
    alerts.publish(new Alert(type, userId.toString(), reason));
  }

  private static void validate(CreateGoalRequest request, Instant now) {
    if (!request.deadline().isAfter(now)) {
      throw OrganizerGoalException.invalid("deadline must be in the future");
    }
    if (request.targetType() == TargetType.FIXED) {
      if (request.fixedAmountPesewas() == null || request.fixedAmountPesewas() <= 0) {
        throw OrganizerGoalException.invalid("fixedAmountPesewas is required for a fixed goal");
      }
    } else if (request.minimumAmountPesewas() == null || request.minimumAmountPesewas() < 1_000) {
      throw OrganizerGoalException.invalid("minimumAmountPesewas must be at least 1000");
    }
    if (request.targetAmountPesewas() != null && request.targetAmountPesewas() <= 0) {
      throw OrganizerGoalException.invalid("targetAmountPesewas must be positive");
    }
    if (request.secondApproverThresholdPesewas() != null
        && request.secondApproverThresholdPesewas() < 0) {
      throw OrganizerGoalException.invalid("secondApproverThresholdPesewas cannot be negative");
    }
  }

  private record ApproverIdentity(String name, byte[] ciphertext, byte[] hash, Instant setAt) {}

  private static String clean(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
