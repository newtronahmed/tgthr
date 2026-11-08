package com.groupcollections.ledger;

/**
 * A non-negative amount in integer minor units with an ISO-4217 currency code. Floating point never
 * touches money (Constitution I.3, INV-LEDGER-005). Posted ledger entries must be strictly
 * positive; use {@link #isPositive()} to check.
 */
public record Money(long minorUnits, String currencyCode) {

  public static final Money ZERO = new Money(0, "GHS");

  public Money {
    if (minorUnits < 0) {
      throw new IllegalArgumentException("amount must not be negative: " + minorUnits);
    }
    if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("currency must be a 3-letter ISO code: " + currencyCode);
    }
  }

  public static Money of(long minorUnits, String currencyCode) {
    return new Money(minorUnits, currencyCode);
  }

  /** Adds two amounts, failing loudly on overflow or currency mismatch. */
  public Money plus(Money other) {
    if (!currencyCode.equals(other.currencyCode)) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currencyCode + " and " + other.currencyCode);
    }
    return new Money(Math.addExact(minorUnits, other.minorUnits), currencyCode);
  }

  /** Subtracts two amounts, failing on overflow, currency mismatch or a negative result. */
  public Money minus(Money other) {
    if (!currencyCode.equals(other.currencyCode)) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currencyCode + " and " + other.currencyCode);
    }
    return new Money(Math.subtractExact(minorUnits, other.minorUnits), currencyCode);
  }

  public boolean isPositive() {
    return minorUnits > 0;
  }
}
