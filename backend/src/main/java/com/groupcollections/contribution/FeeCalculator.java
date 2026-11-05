package com.groupcollections.contribution;

import com.groupcollections.ledger.Money;

public final class FeeCalculator {

  private static final long BASIS_POINTS = 10_000L;

  private final int platformFeeBps;
  private final int paystackFeeBps;

  public FeeCalculator(int platformFeeBps, int paystackFeeBps) {
    if (platformFeeBps < 0) {
      throw new IllegalArgumentException("platformFeeBps must not be negative");
    }
    if (paystackFeeBps < 0 || paystackFeeBps >= BASIS_POINTS) {
      throw new IllegalArgumentException("paystackFeeBps must be between 0 and 9999");
    }
    this.platformFeeBps = platformFeeBps;
    this.paystackFeeBps = paystackFeeBps;
  }

  public FeeBreakdown calculate(Money amount) {
    long amountPesewas = amount.pesewas();
    long platformFeePesewas =
        ceilDiv(Math.multiplyExact(amountPesewas, platformFeeBps), BASIS_POINTS);
    long grossBasePesewas = Math.addExact(amountPesewas, platformFeePesewas);
    long totalPesewas =
        ceilDiv(Math.multiplyExact(grossBasePesewas, BASIS_POINTS), BASIS_POINTS - paystackFeeBps);
    long processingFeePesewas = Math.subtractExact(totalPesewas, grossBasePesewas);

    return new FeeBreakdown(
        Money.ofPesewas(amountPesewas),
        Money.ofPesewas(processingFeePesewas),
        Money.ofPesewas(platformFeePesewas),
        Money.ofPesewas(totalPesewas));
  }

  private static long ceilDiv(long numerator, long denominator) {
    long quotient = Math.floorDiv(numerator, denominator);
    return Math.floorMod(numerator, denominator) == 0 ? quotient : quotient + 1;
  }
}
