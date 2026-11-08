package com.groupcollections.goal;

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
  private final TransactionTemplate transactionTemplate;

  public OrganizerGoalService(
      OrganizerGoalRepository goals,
      UserRepository users,
      PhoneCipher phones,
      AccountResolver resolver,
      SlugGenerator slugs,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.goals = goals;
    this.users = users;
    this.phones = phones;
    this.resolver = resolver;
    this.slugs = slugs;
    this.clock = clock;
    this.meters = meters;
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

    Instant now = Instant.now(clock);
    validate(request, now);
    String legalName = clean(user.legalName());
    if (request.destinationType() == DestinationType.ADMIN_MOMO && legalName == null) {
      destinationFailed("legal_name");
      throw OrganizerGoalException.legalNameRequired();
    }

    String accountName = resolve(request);
    if (accountName == null || accountName.isBlank()) {
      destinationFailed("unresolved");
      throw OrganizerGoalException.destinationUnverified();
    }
    if (request.destinationType() == DestinationType.ADMIN_MOMO
        && !NameMatcher.matches(accountName, legalName)) {
      destinationFailed("name_mismatch");
      throw OrganizerGoalException.destinationUnverified();
    }
    if (request.destinationType() == DestinationType.ADMIN_MOMO && !ownsPhone(user, request)) {
      destinationFailed("not_owner");
      throw OrganizerGoalException.destinationUnverified();
    }

    String normalizedDestination = PhoneNormalizer.normalize(request.destinationPhone());
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

  private String resolve(CreateGoalRequest request) {
    try {
      return resolver.resolve(request.destinationPhone(), request.network());
    } catch (OrganizerGoalException failure) {
      destinationFailed("unavailable");
      log.warn("destination resolution unavailable");
      throw failure;
    }
  }

  private boolean ownsPhone(OrganizerUser user, CreateGoalRequest request) {
    String normalized = PhoneNormalizer.normalize(request.destinationPhone());
    return users
        .findPhoneHash(user.id())
        .map(hash -> MessageDigest.isEqual(hash, phones.hash(normalized)))
        .orElse(false);
  }

  private void destinationFailed(String reason) {
    meters.counter("destination.resolve.failed", "reason", reason).increment();
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

  private static String clean(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
