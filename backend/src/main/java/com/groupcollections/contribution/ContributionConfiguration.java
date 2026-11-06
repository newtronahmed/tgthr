package com.groupcollections.contribution;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ContributionConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  FeeCalculator feeCalculator(
      @Value("${collections.contribution.platform-fee-bps}") int platformFeeBps,
      @Value("${collections.contribution.paystack-fee-bps}") int paystackFeeBps) {
    return new FeeCalculator(platformFeeBps, paystackFeeBps);
  }

  @Bean
  PayerPhoneCipher payerPhoneCipher(
      @Value("${collections.security.payer-phone-key}") String base64MasterKey) {
    return new PayerPhoneCipher(base64MasterKey);
  }
}
