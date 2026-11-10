package com.groupcollections.payout;

import com.groupcollections.ledger.Money;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Provider-independent fee fallback used until the Paystack transfer adapter is verified (T301).
 * The configured value is the all-in transfer fee; the payout worker re-quotes before transfer.
 */
@Component
@ConditionalOnProperty(name = "collections.payout.fee-provider", havingValue = "configured")
public class ConfiguredPayoutFeeProvider implements PayoutFeeProvider {

  private final long feeMinorUnits;

  public ConfiguredPayoutFeeProvider(
      @Value("${collections.payout.transfer-fee-minor-units}") long feeMinorUnits) {
    if (feeMinorUnits < 0) {
      throw new IllegalArgumentException("payout transfer fee cannot be negative");
    }
    this.feeMinorUnits = feeMinorUnits;
  }

  @Override
  public Money quote(UUID goalId, Money amount) {
    return Money.of(feeMinorUnits, amount.currencyCode());
  }
}
