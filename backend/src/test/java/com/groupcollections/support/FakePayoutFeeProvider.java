package com.groupcollections.support;

import com.groupcollections.ledger.Money;
import com.groupcollections.payout.PayoutFeeProvider;
import java.util.UUID;

/** Deterministic payout fee for tests; never calls a provider. */
public final class FakePayoutFeeProvider implements PayoutFeeProvider {

  private static volatile long feeMinorUnits = 100;

  @Override
  public Money quote(UUID goalId, Money amount) {
    return Money.of(feeMinorUnits, amount.currencyCode());
  }

  public static void setFee(long fee) {
    feeMinorUnits = fee;
  }

  public static void reset() {
    feeMinorUnits = 100;
  }
}
