package com.groupcollections.operator;

import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorConfiguration {

  @Bean
  PasswordHasher operatorPasswordHasher() {
    return new PasswordHasher();
  }

  @Bean
  OperatorSecretCipher operatorSecretCipher(
      @Value("${collections.operator.totp-secret-key:}") String base64Key,
      SecureRandom secureRandom) {
    return new OperatorSecretCipher(base64Key, secureRandom);
  }
}
