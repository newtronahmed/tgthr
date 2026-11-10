package com.groupcollections.payout;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.goal.GoalStatus;
import com.groupcollections.goal.OrganizerGoal;
import com.groupcollections.goal.OrganizerGoalRepository;
import com.groupcollections.ledger.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Claims one READY payout under the goal lock and re-checks the fee before transfer. */
@Service
@ConditionalOnProperty(name = "collections.payout.execution.enabled", havingValue = "true")
public class PayoutClaimService {

  private final PayoutRepository payouts;
  private final OrganizerGoalRepository goals;
  private final PayoutFeeProvider fees;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;

  public PayoutClaimService(
      PayoutRepository payouts,
      OrganizerGoalRepository goals,
      PayoutFeeProvider fees,
      MeterRegistry meters,
      AlertPublisher alerts) {
    this.payouts = payouts;
    this.goals = goals;
    this.fees = fees;
    this.meters = meters;
    this.alerts = alerts;
  }

  @Transactional
  public Optional<Payout> claimNext(Instant now) {
    List<UUID> ready = payouts.findReadyForExecution();
    for (UUID id : ready) {
      Payout payout = payouts.findById(id).orElse(null);
      if (payout == null || !goals.lockByIdAndCreator(payout.goalId(), payout.requestedBy())) {
        continue;
      }
      OrganizerGoal goal =
          goals.findByIdAndCreator(payout.goalId(), payout.requestedBy()).orElse(null);
      if (goal == null) {
        continue;
      }
      if (goal.status() == GoalStatus.FROZEN) {
        meters.counter("payout.execution.frozen_blocked").increment();
        alerts.publish(
            new Alert(
                Alert.AlertType.PAYOUT_BLOCKED_FROZEN,
                id.toString(),
                "payout is READY but its goal is frozen"));
        continue;
      }
      Money quoted =
          fees.quote(payout.goalId(), Money.of(payout.amountPesewas(), payout.currency()));
      if (quoted.minorUnits() != payout.feePesewas()) {
        PayoutStateMachine.transition(
            PayoutStateMachine.PayoutState.READY,
            PayoutStateMachine.PayoutEvent.FEE_MISMATCH,
            false);
        payouts.markFailed(id, "PAYOUT_FEE_MISMATCH", "READY", now);
        payouts.appendEvent(id, "FEE_MISMATCH", "READY", "FAILED", now);
        meters.counter("payout.fee_mismatch").increment();
        alerts.publish(
            new Alert(
                Alert.AlertType.PAYOUT_FEE_MISMATCH,
                id.toString(),
                "payout fee changed before execution"));
        continue;
      }
      PayoutStateMachine.transition(
          PayoutStateMachine.PayoutState.READY, PayoutStateMachine.PayoutEvent.CLAIM, false);
      if (payouts.claimForExecution(id, now) == 1) {
        payouts.appendEvent(id, "EXECUTING", "READY", "EXECUTING", now);
        return Optional.of(payout);
      }
    }
    return Optional.empty();
  }
}
