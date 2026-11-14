package com.groupcollections.identity;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Arkesel SMS v2 adapter. The OTP is generated and verified locally; Arkesel only delivers the
 * message. Sandbox mode is free and never reaches the mobile networks.
 */
@Component
@ConditionalOnProperty(name = "collections.sms.provider", havingValue = "arkesel")
public class ArkeselSmsSender implements SmsSender {

  private final RestClient restClient;
  private final String apiKey;
  private final String senderId;
  private final boolean sandbox;
  private final MeterRegistry meters;

  public ArkeselSmsSender(
      @Value("${collections.sms.arkesel.base-url:https://sms.arkesel.com}") String baseUrl,
      @Value("${collections.sms.arkesel.api-key}") String apiKey,
      @Value("${collections.sms.arkesel.sender-id}") String senderId,
      @Value("${collections.sms.arkesel.sandbox:false}") boolean sandbox,
      @Value("${collections.sms.arkesel.connect-timeout:PT3S}") Duration connectTimeout,
      @Value("${collections.sms.arkesel.read-timeout:PT5S}") Duration readTimeout,
      MeterRegistry meters) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException(
          "ARKESEL_SMS_API_KEY is required when SMS_PROVIDER=arkesel");
    }
    if (senderId == null || senderId.isBlank() || senderId.length() > 11) {
      throw new IllegalArgumentException("ARKESEL_SENDER_ID must be 1-11 characters");
    }
    this.apiKey = apiKey;
    this.senderId = senderId;
    this.sandbox = sandbox;
    this.meters = meters;
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).build();
  }

  @Override
  public void send(String phoneE164, String message) {
    String recipient = providerNumber(phoneE164);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sender", senderId);
    body.put("message", message == null ? "" : message);
    body.put("recipients", List.of(recipient));
    body.put("sandbox", sandbox);
    try {
      JsonNode response =
          restClient
              .post()
              .uri("/api/v2/sms/send")
              .header("api-key", apiKey)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (response == null || !"success".equals(response.path("status").asText())) {
        failure("provider_rejected");
        throw IdentityException.smsUnavailable();
      }
    } catch (RestClientResponseException failure) {
      failure(statusReason(failure.getStatusCode().value()));
      throw IdentityException.smsUnavailable();
    } catch (ResourceAccessException failure) {
      failure("timeout");
      throw IdentityException.smsUnavailable();
    } catch (RestClientException failure) {
      failure("transport");
      throw IdentityException.smsUnavailable();
    }
  }

  private void failure(String reason) {
    meters.counter("sms.provider.failure", "reason", reason).increment();
  }

  private static String statusReason(int status) {
    return switch (status) {
      case 401, 403 -> "unauthorized";
      case 402 -> "insufficient_balance";
      case 422 -> "invalid_request";
      case 429 -> "rate_limited";
      default -> status >= 500 ? "server_error" : "provider_rejected";
    };
  }

  /** Arkesel examples use the country code without `+`; accept E.164 or local input. */
  private static String providerNumber(String phone) {
    String normalized = PhoneNormalizer.normalize(phone);
    return normalized.startsWith("+") ? normalized.substring(1) : normalized;
  }
}
