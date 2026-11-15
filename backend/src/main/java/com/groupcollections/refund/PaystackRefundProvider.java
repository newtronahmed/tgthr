package com.groupcollections.refund;

import com.fasterxml.jackson.databind.JsonNode;
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
      return map(response);
    } catch (RestClientResponseException failure) {
      return transientFailure(failure.getStatusCode())
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
      return map(response);
    } catch (RestClientException failure) {
      return RefundResult.pending(refund.providerReference());
    }
  }

  private static boolean transientFailure(HttpStatusCode status) {
    return status.value() == 429 || status.is5xxServerError();
  }

  private static RefundResult map(JsonNode response) {
    if (response == null || !response.path("status").asBoolean(false)) {
      return RefundResult.failed("PROVIDER_REJECTED");
    }
    JsonNode data = response.path("data");
    String reference =
        data.hasNonNull("id")
            ? data.get("id").asText()
            : data.hasNonNull("reference") ? data.get("reference").asText() : null;
    String status = data.path("status").asText("");
    return switch (status) {
      case "processed", "success" -> RefundResult.success(reference);
      case "failed", "reversed" -> RefundResult.failed("PROVIDER_REFUND_FAILED");
      default -> RefundResult.pending(reference);
    };
  }
}
