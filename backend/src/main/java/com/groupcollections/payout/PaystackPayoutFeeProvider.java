package com.groupcollections.payout;

import com.groupcollections.ledger.Money;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Paystack Ghana payout fee. Every payout destination in this feature is mobile money; Paystack
 * charges GHS 1 per successful MoMo transfer (GHS 8 applies to bank transfers, which are not a
 * modeled destination).
 */
@Component
@ConditionalOnProperty(name = "collections.payout.fee-provider", havingValue = "paystack")
public class PaystackPayoutFeeProvider implements PayoutFeeProvider {

  private final long feeMinorUnits;

  public PaystackPayoutFeeProvider(
      @Value("${collections.payout.paystack.momo-fee-minor-units:100}") long feeMinorUnits) {
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
