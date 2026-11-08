package com.groupcollections.contribution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ContributionConfiguration {

  @Bean
  FeeCalculator feeCalculator(
      @Value("${collections.contribution.platform-fee-bps}") int platformFeeBps,
      @Value("${collections.contribution.paystack-fee-bps}") int paystackFeeBps) {
    return new FeeCalculator(platformFeeBps, paystackFeeBps);
  }
}
