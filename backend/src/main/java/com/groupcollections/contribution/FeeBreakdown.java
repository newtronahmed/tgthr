package com.groupcollections.contribution;

import com.groupcollections.ledger.Money;
import java.util.Objects;

public record FeeBreakdown(Money amount, Money processingFee, Money platformFee, Money total) {

  public FeeBreakdown {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(processingFee, "processingFee");
    Objects.requireNonNull(platformFee, "platformFee");
    Objects.requireNonNull(total, "total");
    Money expected = amount.plus(processingFee).plus(platformFee);
    if (!expected.equals(total)) {
      throw new IllegalArgumentException("total must equal amount plus fees");
    }
  }
}
