package com.groupcollections.payout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starts the mandatory hold and moves expired holds to READY. */
@Service
public class PayoutHoldService {

  private final PayoutRepository payouts;
  private final Clock clock;
  private final int minimumHoldMinutes;

  public PayoutHoldService(
      PayoutRepository payouts,
      Clock clock,
      @Value("${collections.payout.minimum-hold-minutes:1}") int minimumHoldMinutes) {
    this.payouts = payouts;
    this.clock = clock;
    this.minimumHoldMinutes = minimumHoldMinutes;
    if (minimumHoldMinutes < 1) {
      throw new IllegalArgumentException(
          "collections.payout.minimum-hold-minutes must be at least 1");
    }
  }

  @Transactional
  public void startHold(UUID payoutId, int holdWindowMinutes, Instant now) {
    if (holdWindowMinutes < minimumHoldMinutes) {
      throw PayoutException.holdRequired();
    }
    PayoutStateMachine.transition(
        PayoutStateMachine.PayoutState.APPROVED, PayoutStateMachine.PayoutEvent.START_HOLD, false);
    Instant expiresAt = now.plus(Duration.ofMinutes(holdWindowMinutes));
    if (payouts.startHold(payoutId, expiresAt, now) != 1) {
      throw new IllegalStateException("payout is not approved");
    }
    payouts.appendEvent(payoutId, "HOLD", "APPROVED", "HOLD", now);
  }

  @Transactional
  public int releaseExpired() {
    Instant now = Instant.now(clock);
    List<UUID> expired = payouts.expiredHolds(now);
    int moved = 0;
    for (UUID id : expired) {
      PayoutStateMachine.transition(
          PayoutStateMachine.PayoutState.HOLD, PayoutStateMachine.PayoutEvent.HOLD_EXPIRED, false);
      if (payouts.markReady(id, now) == 1) {
        payouts.appendEvent(id, "READY", "HOLD", "READY", now);
        moved++;
      }
    }
    return moved;
  }
}
