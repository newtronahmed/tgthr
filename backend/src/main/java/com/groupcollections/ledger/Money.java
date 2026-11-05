package com.groupcollections.ledger;

/**
 * A non-negative amount in integer pesewas (the GHS minor unit). Floating point never touches money
 * (Constitution I.3, INV-LEDGER-005). Posted ledger entries must be strictly positive; use {@link
 * #isPositive()} to check.
 */
public record Money(long pesewas) {

  public static final String CURRENCY = "GHS";
  public static final Money ZERO = new Money(0);

  public Money {
    if (pesewas < 0) {
      throw new IllegalArgumentException("amount must not be negative: " + pesewas);
    }
  }

  public static Money ofPesewas(long pesewas) {
    return new Money(pesewas);
  }

  /** Adds two amounts, failing loudly on overflow rather than wrapping. */
  public Money plus(Money other) {
    return new Money(Math.addExact(pesewas, other.pesewas));
  }

  public boolean isPositive() {
    return pesewas > 0;
  }
}
