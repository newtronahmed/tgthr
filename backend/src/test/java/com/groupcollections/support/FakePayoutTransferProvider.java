package com.groupcollections.support;

import com.groupcollections.payout.Payout;
import com.groupcollections.payout.PayoutTransferProvider;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic transfer provider for execution tests; never calls a real provider. */
public final class FakePayoutTransferProvider implements PayoutTransferProvider {

  private static final AtomicInteger CALLS = new AtomicInteger();
  private static volatile TransferResult.Status status = TransferResult.Status.SUCCESS;
  private static volatile boolean throwNext;

  @Override
  public TransferResult transfer(Payout payout) {
    CALLS.incrementAndGet();
    if (throwNext) {
      throwNext = false;
      throw new IllegalStateException("fake provider failure");
    }
    return switch (status) {
      case SUCCESS -> new TransferResult(status, "fake-transfer-" + payout.id(), null);
      case FAILED -> new TransferResult(status, null, "PROVIDER_FAILED");
      case UNKNOWN -> new TransferResult(status, null, null);
    };
  }

  public static void status(TransferResult.Status value) {
    status = value;
  }

  public static int calls() {
    return CALLS.get();
  }

  public static void throwNext() {
    throwNext = true;
  }

  public static void reset() {
    status = TransferResult.Status.SUCCESS;
    throwNext = false;
    CALLS.set(0);
  }
}
