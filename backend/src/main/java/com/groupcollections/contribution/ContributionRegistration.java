package com.groupcollections.contribution;

import com.groupcollections.goal.Goal;
import com.groupcollections.goal.GoalNotAcceptingException;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.GoalRepository;
import com.groupcollections.ledger.Money;
import com.groupcollections.security.PhoneCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers a contribution while holding a shared lock on the goal row, so a concurrent freeze or
 * close cannot slip between the acceptance check and the insert.
 */
@Service
public class ContributionRegistration {

  private static final Logger log = LoggerFactory.getLogger(ContributionRegistration.class);

  public record Registration(
      ContributionStatus status, UUID id, long totalPesewas, boolean created) {}

  private final GoalRepository goals;
  private final ContributionRepository contributions;
  private final FeeCalculator fees;
  private final PhoneCipher phones;
  private final IdGenerator ids;
  private final MeterRegistry meters;
  private final Clock clock;

  public ContributionRegistration(
      GoalRepository goals,
      ContributionRepository contributions,
      FeeCalculator fees,
      PhoneCipher phones,
      IdGenerator ids,
      MeterRegistry meters,
      Clock clock) {
    this.goals = goals;
    this.contributions = contributions;
    this.fees = fees;
    this.phones = phones;
    this.ids = ids;
    this.meters = meters;
    this.clock = clock;
  }

  @Transactional
  public Registration register(
      String slug, String idempotencyKey, CreateContributionRequest request) {
    Optional<ContributionStatus> original = contributions.findByIdempotencyKey(idempotencyKey);
    if (original.isPresent()) {
      return new Registration(original.get(), null, 0, false);
    }

    Goal goal = goals.findBySlugForShare(slug).orElseThrow(() -> new GoalNotFoundException(slug));
    if (!goal.acceptsContributions(Instant.now(clock))) {
      throw new GoalNotAcceptingException(slug);
    }
    Money amount = Money.of(request.amountPesewas(), "GHS");
    goal.rules().check(amount);
    FeeBreakdown breakdown = fees.calculate(amount);
    UUID id = ids.next();

    boolean inserted =
        contributions.insertInitiated(id, goal.id(), breakdown, request, phones, idempotencyKey);
    if (!inserted) {
      ContributionStatus existing =
          contributions
              .findByIdempotencyKey(idempotencyKey)
              .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
      return new Registration(existing, null, 0, false);
    }

    meters.counter("contributions.created").increment();
    log.info(
        "event=contribution_created contributionId={} goalId={} amountPesewas={}",
        id,
        goal.id(),
        amount.minorUnits());
    return new Registration(contributions.status(id), id, breakdown.total().minorUnits(), true);
  }
}
