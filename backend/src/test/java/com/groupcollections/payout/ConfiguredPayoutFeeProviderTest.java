package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConfiguredPayoutFeeProviderTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ConfiguredPayoutFeeProvider.class)
          .withPropertyValues("collections.payout.fee-provider=configured");

  @Test
  void failsWhenTheConfiguredFeeIsMissing() {
    runner.run(context -> assertThat(context).hasFailed());
  }

  @Test
  void acceptsAConfiguredFee() {
    runner
        .withPropertyValues("collections.payout.transfer-fee-minor-units=100")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
