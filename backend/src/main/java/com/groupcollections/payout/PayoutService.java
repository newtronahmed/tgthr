package com.groupcollections.payout;

import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.OrganizerGoal;
import com.groupcollections.goal.OrganizerGoalRepository;
import com.groupcollections.goal.SecondApprover;
import com.groupcollections.identity.OrganizerUser;
import com.groupcollections.ledger.Money;
import com.groupcollections.settlement.SettlementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PayoutService {

  private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

  private static final String CURRENCY = "GHS";

  private final OrganizerGoalRepository goals;
  private final PayoutRepository payouts;
  private final SettlementRepository settlements;
  private final PayoutFeeProvider fees;
  private final PayoutHoldService holds;
  private final Clock clock;
  private final MeterRegistry meters;

  public PayoutService(
      OrganizerGoalRepository goals,
      PayoutRepository payouts,
      SettlementRepository settlements,
      PayoutFeeProvider fees,
      PayoutHoldService holds,
      Clock clock,
      MeterRegistry meters) {
    this.goals = goals;
    this.payouts = payouts;
    this.settlements = settlements;
    this.fees = fees;
    this.holds = holds;
    this.clock = clock;
    this.meters = meters;
  }

  @Transactional(readOnly = true)
  public List<PayoutView> list(OrganizerUser user, UUID goalId) {
    ownedGoal(user, goalId);
    return payouts.findByGoalAndRequester(goalId, user.id()).stream()
        .map(PayoutService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public PayoutQuote quote(OrganizerUser user, UUID goalId, long amountPesewas) {
    OrganizerGoal goal = ownedGoal(user, goalId);
    checkAmount(amountPesewas);
    Money fee = fees.quote(goalId, Money.of(amountPesewas, CURRENCY));
    long net = amountPesewas - fee.minorUnits();
    if (net <= 0) {
      throw PayoutException.amountTooLow();
    }
    return new PayoutQuote(amountPesewas, fee.minorUnits(), net, available(goalId), CURRENCY);
  }

  @Transactional
  public PayoutView request(
      OrganizerUser user, UUID goalId, String idempotencyKey, long amountPesewas) {
    validateKey(idempotencyKey);
    if (!goals.lockByIdAndCreator(goalId, user.id())) {
      throw new GoalNotFoundException(goalId.toString());
    }
    OrganizerGoal goal =
        goals
            .findByIdAndCreator(goalId, user.id())
            .orElseThrow(() -> new GoalNotFoundException(goalId.toString()));

    Optional<Payout> original = payouts.findByGoalAndIdempotencyKey(goalId, idempotencyKey);
    if (original.isPresent()) {
      if (original.get().amountPesewas() != amountPesewas) {
        throw PayoutException.idempotencyConflict();
      }
      return view(original.get());
    }

    if (goal.status().name().equals("FROZEN")) {
      throw reject("goal_frozen", PayoutException.goalFrozen());
    }
    if (goal.secondApprover() != SecondApprover.OFF && goal.secondApproverName() == null) {
      throw reject("approver_required", PayoutException.approverRequired());
    }
    if (goal.holdWindowMinutes() < 1) {
      throw reject("hold_required", PayoutException.holdRequired());
    }
    boolean approvalRequired = approvalRequired(goal, amountPesewas);
    checkAmount(amountPesewas);
    Money fee = fees.quote(goalId, Money.of(amountPesewas, CURRENCY));
    if (amountPesewas <= fee.minorUnits()) {
      throw reject("amount_too_low", PayoutException.amountTooLow());
    }
    if (amountPesewas > available(goalId)) {
      throw reject("insufficient_settled_balance", PayoutException.insufficientSettledBalance());
    }

    Instant now = Instant.now(clock);
    UUID id =
        payouts.insert(
            goalId,
            user.id(),
            amountPesewas,
            fee.minorUnits(),
            amountPesewas - fee.minorUnits(),
            "REQUESTED",
            idempotencyKey,
            now);
    if (id == null) {
      Payout concurrent =
          payouts
              .findByGoalAndIdempotencyKey(goalId, idempotencyKey)
              .orElseThrow(() -> new IllegalStateException("payout insert disappeared"));
      if (concurrent.amountPesewas() != amountPesewas) {
        throw reject("idempotency_conflict", PayoutException.idempotencyConflict());
      }
      return view(concurrent);
    }
    payouts.appendEvent(id, "REQUESTED", null, "REQUESTED", now);
    String state = "REQUESTED";
    if (!approvalRequired) {
      PayoutStateMachine.transition(
          PayoutStateMachine.PayoutState.REQUESTED, PayoutStateMachine.PayoutEvent.APPROVE, false);
      payouts.markApproved(id, now);
      payouts.appendEvent(id, "APPROVED", "REQUESTED", "APPROVED", now);
      holds.startHold(id, goal.holdWindowMinutes(), now);
      state = "HOLD";
    }
    meters.counter("payout.requests").increment();
    log.info("payout requested {}", id);
    return new PayoutView(
        id,
        goalId,
        amountPesewas,
        fee.minorUnits(),
        amountPesewas - fee.minorUnits(),
        CURRENCY,
        state,
        now);
  }

  private OrganizerGoal ownedGoal(OrganizerUser user, UUID goalId) {
    return goals
        .findByIdAndCreator(goalId, user.id())
        .orElseThrow(() -> new GoalNotFoundException(goalId.toString()));
  }

  private long available(UUID goalId) {
    long pool = payouts.goalPoolBalance(goalId).minorUnits();
    long settled = settlements.settledGoalAmount(goalId).minorUnits();
    long completed = payouts.completedMinorUnits(goalId);
    long reserved = payouts.reservedMinorUnits(goalId);
    long settledAfterCompleted = Math.max(0, settled - completed);
    return Math.max(0, Math.min(pool, settledAfterCompleted) - reserved);
  }

  private PayoutException reject(String reason, PayoutException failure) {
    meters.counter("payout.rejected", "reason", reason).increment();
    log.info("payout rejected {}", reason);
    return failure;
  }

  private static boolean approvalRequired(OrganizerGoal goal, long amountPesewas) {
    return goal.secondApprover() == SecondApprover.ALWAYS
        || (goal.secondApprover() == SecondApprover.ABOVE_THRESHOLD
            && amountPesewas >= goal.secondApproverThresholdPesewas());
  }

  private static void checkAmount(long amountPesewas) {
    if (amountPesewas <= 0) {
      throw PayoutException.invalid("amountPesewas must be positive");
    }
  }

  private static void validateKey(String idempotencyKey) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() < 8
        || idempotencyKey.length() > 64) {
      throw PayoutException.invalid("Idempotency-Key must be 8 to 64 characters");
    }
  }

  private static PayoutView view(Payout payout) {
    return new PayoutView(
        payout.id(),
        payout.goalId(),
        payout.amountPesewas(),
        payout.feePesewas(),
        payout.netPesewas(),
        payout.currency(),
        payout.state(),
        payout.createdAt());
  }
}
