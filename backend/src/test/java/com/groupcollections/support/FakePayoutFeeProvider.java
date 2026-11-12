package com.groupcollections.support;

import com.groupcollections.ledger.Money;
import com.groupcollections.payout.PayoutFeeProvider;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic payout fee for tests; never calls a provider. */
public final class FakePayoutFeeProvider implements PayoutFeeProvider {

  private static final Object GATE = new Object();
  private static volatile long feeMinorUnits = 100;
  private static CountDownLatch entered = new CountDownLatch(0);
  private static CountDownLatch release = new CountDownLatch(0);

  @Override
  public Money quote(UUID goalId, Money amount) {
    CountDownLatch enteredLatch;
    CountDownLatch releaseLatch;
    synchronized (GATE) {
      enteredLatch = entered;
      releaseLatch = release;
    }
    if (enteredLatch.getCount() > 0) {
      enteredLatch.countDown();
      try {
        if (!releaseLatch.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("payout fee quote was not released");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("payout fee quote interrupted", failure);
      }
    }
    return Money.of(feeMinorUnits, amount.currencyCode());
  }

  public static void setFee(long fee) {
    feeMinorUnits = fee;
  }

  /** Blocks the next quote after signalling {@link #awaitEntered}. */
  public static void blockNext() {
    synchronized (GATE) {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }
  }

  public static boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
    CountDownLatch latch;
    synchronized (GATE) {
      latch = entered;
    }
    return latch.await(timeout, unit);
  }

  public static void release() {
    CountDownLatch latch;
    synchronized (GATE) {
      latch = release;
    }
    latch.countDown();
  }

  public static void reset() {
    feeMinorUnits = 100;
    synchronized (GATE) {
      entered = new CountDownLatch(0);
      release = new CountDownLatch(0);
    }
  }
}
