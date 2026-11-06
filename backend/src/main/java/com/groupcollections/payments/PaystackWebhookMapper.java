package com.groupcollections.payments;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Maps Paystack's charge webhook payload to the internal {@link PaymentResult}. */
@Component
public class PaystackWebhookMapper {

  private final ObjectMapper mapper;

  public PaystackWebhookMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public PaymentResult map(String rawBody) {
    try {
      PaystackWebhook webhook = mapper.readValue(rawBody, PaystackWebhook.class);
      PaystackWebhook.Data data = webhook.data();
      if (data == null) {
        throw new InvalidWebhookPayloadException("Paystack webhook is missing data");
      }
      boolean success = "charge.success".equals(webhook.event());
      if (!success && !"charge.failed".equals(webhook.event())) {
        throw new InvalidWebhookPayloadException("unsupported Paystack event");
      }
      if (data.reference() == null || data.reference().isBlank()) {
        throw new InvalidWebhookPayloadException("Paystack webhook is missing the reference");
      }
      if (data.amount() <= 0) {
        throw new InvalidWebhookPayloadException("Paystack webhook has an invalid amount");
      }
      if (!(success ? "success" : "failed").equals(data.status())) {
        throw new InvalidWebhookPayloadException("Paystack event and status disagree");
      }
      if (!"GHS".equals(data.currency())) {
        throw new InvalidWebhookPayloadException("unsupported currency");
      }
      return new PaymentResult(
          data.reference(), success, data.amount(), data.paidAt(), data.currency());
    } catch (JsonProcessingException failure) {
      throw new InvalidWebhookPayloadException("invalid Paystack webhook payload");
    }
  }

  record PaystackWebhook(String event, Data data) {
    record Data(
        String reference,
        String status,
        long amount,
        String currency,
        @JsonProperty("paid_at") Instant paidAt) {}
  }
}
