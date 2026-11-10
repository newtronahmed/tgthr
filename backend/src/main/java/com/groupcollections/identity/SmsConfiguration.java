package com.groupcollections.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SmsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SmsConfiguration.class);

  @Bean
  @ConditionalOnProperty(name = "collections.sms.provider", havingValue = "logging")
  SmsSender loggingSmsSender() {
    return (phone, message) -> log.warn("development SMS sender used; message redacted");
  }

  @Bean
  @ConditionalOnProperty(
      name = "collections.sms.provider",
      havingValue = "none",
      matchIfMissing = true)
  SmsSender unconfiguredSmsSender() {
    return (phone, message) -> {
      throw IdentityException.smsUnavailable();
    };
  }
}
