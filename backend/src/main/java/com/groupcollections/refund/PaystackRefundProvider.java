package com.groupcollections.refund;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Paystack refund adapter. Tests use the shared fake server; no live provider is reachable. */
@Component
@ConditionalOnProperty(name = "collections.refunds.provider", havingValue = "paystack")
public class PaystackRefundProvider implements RefundProvider {

  private final RestClient restClient;

  public PaystackRefundProvider(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public RefundResult refund(Refund refund) {
    if (refund.providerTransactionReference() == null
        || refund.providerTransactionReference().isBlank()) {
      return RefundResult.failed("PROVIDER_REFERENCE_MISSING");
    }
    try {
      JsonNode response =
          restClient
              .post()
              .uri("/refund")
              .body(
                  Map.of(
                      "transaction",
                      refund.providerTransactionReference(),
                      "amount",
                      refund.totalMinorUnits(),
                      "currency",
                      refund.currencyCode(),
                      "merchant_note",
                      "Refund " + refund.id()))
              .retrieve()
              .body(JsonNode.class);
      return map(response, refund);
    } catch (RestClientResponseException failure) {
      return transientFailure(failure.getStatusCode()) || duplicateRefund(failure)
          ? RefundResult.pending(null)
          : RefundResult.failed("PROVIDER_REJECTED");
    } catch (RestClientException failure) {
      return RefundResult.pending(null);
    }
  }

  @Override
  public RefundResult verify(Refund refund) {
    try {
      JsonNode response;
      if (refund.providerReference() != null && !refund.providerReference().isBlank()) {
        response =
            restClient
                .get()
                .uri("/refund/{reference}", refund.providerReference())
                .retrieve()
                .body(JsonNode.class);
      } else if (refund.providerTransactionReference() != null
          && !refund.providerTransactionReference().isBlank()) {
        response =
            restClient
                .get()
                .uri("/refund?transaction={transaction}", refund.providerTransactionReference())
                .retrieve()
                .body(JsonNode.class);
      } else {
        return RefundResult.pending(null);
      }
      return map(response, refund);
    } catch (RestClientException failure) {
      return RefundResult.pending(refund.providerReference());
    }
  }

  /**
   * Paystack returns 408 for a request it may still have accepted, so it is treated like a timeout
   * rather than a rejection. A duplicate response means an earlier attempt landed and must be
   * verified instead of retried.
   */
  private static boolean transientFailure(HttpStatusCode status) {
    return status.value() == 408 || status.value() == 429 || status.is5xxServerError();
  }

  private static boolean duplicateRefund(RestClientResponseException failure) {
    String body = failure.getResponseBodyAsString().toLowerCase(Locale.ROOT);
    return body.contains("already exists") || body.contains("duplicate");
  }

  private static RefundResult map(JsonNode response, Refund refund) {
    if (response == null) {
      return RefundResult.pending(null);
    }
    if (!response.path("status").asBoolean(false)) {
      return RefundResult.failed("PROVIDER_REJECTED");
    }
    JsonNode data = response.path("data");
    String reference =
        data.hasNonNull("id")
            ? data.get("id").asText()
            : data.hasNonNull("reference") ? data.get("reference").asText() : null;
    if (!amountMatches(data, refund) || !currencyMatches(data, refund)) {
      return RefundResult.pending(reference);
    }
    String status = data.path("status").asText("");
    return switch (status) {
      case "processed", "success" -> RefundResult.success(reference);
      case "failed", "reversed" -> RefundResult.failed("PROVIDER_REFUND_FAILED");
      default -> RefundResult.pending(reference);
    };
  }

  private static boolean amountMatches(JsonNode data, Refund refund) {
    return data.hasNonNull("amount") && data.get("amount").asLong() == refund.totalMinorUnits();
  }

  private static boolean currencyMatches(JsonNode data, Refund refund) {
    return data.hasNonNull("currency")
        && refund.currencyCode().equalsIgnoreCase(data.get("currency").asText());
  }
}
