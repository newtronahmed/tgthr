package com.groupcollections.security;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SecureRandom secureRandom() {
    return new SecureRandom();
  }

  @Bean
  PhoneCipher phoneCipher(
      @Value("${collections.security.payer-phone-key}") String base64MasterKey,
      SecureRandom secureRandom) {
    return new PhoneCipher(base64MasterKey, secureRandom);
  }
}
