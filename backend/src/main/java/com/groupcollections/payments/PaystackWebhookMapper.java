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
      if (webhook.data() == null) {
        throw new InvalidWebhookPayloadException("Paystack webhook is missing data");
      }
      return new PaymentResult(
          webhook.data().reference(),
          "charge.success".equals(webhook.event()),
          webhook.data().amount(),
          webhook.data().paidAt());
    } catch (JsonProcessingException failure) {
      throw new InvalidWebhookPayloadException("invalid Paystack webhook payload");
    }
  }

  record PaystackWebhook(String event, Data data) {
    record Data(
        String reference, String status, long amount, @JsonProperty("paid_at") Instant paidAt) {}
  }
}
