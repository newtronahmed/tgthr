package com.groupcollections.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class PaystackConfiguration {

  @Bean
  PaystackClient paystackClient(
      @Value("${collections.payments.paystack.base-url}") String baseUrl,
      @Value("${collections.payments.paystack.secret-key}") String secretKey) {
    RestClient restClient =
        RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
            .build();
    return new PaystackHttpClient(restClient);
  }

  @Bean
  PaystackSignature paystackSignature(
      @Value("${collections.payments.paystack.secret-key}") String secretKey) {
    return new PaystackSignature(secretKey);
  }
}
