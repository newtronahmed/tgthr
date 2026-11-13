package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.ledger.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaystackPayoutFeeProviderTest {

  @Test
  void quotesTheConfiguredMomoFee() {
    PaystackPayoutFeeProvider fees = new PaystackPayoutFeeProvider(100);

    assertThat(fees.quote(UUID.randomUUID(), Money.of(1_000, "GHS")))
        .isEqualTo(Money.of(100, "GHS"));
  }

  @Test
  void rejectsANegativeFee() {
    assertThatThrownBy(() -> new PaystackPayoutFeeProvider(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
