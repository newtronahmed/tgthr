package com.groupcollections.payments;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class PaystackConfiguration {

  @Bean
  PaystackClient paystackClient(
      @Value("${collections.payments.paystack.base-url}") String baseUrl,
      @Value("${collections.payments.paystack.secret-key}") String secretKey,
      @Value("${collections.payments.paystack.connect-timeout}") Duration connectTimeout,
      @Value("${collections.payments.paystack.read-timeout}") Duration readTimeout) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    RestClient restClient =
        RestClient.builder()
            .requestFactory(requestFactory)
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
