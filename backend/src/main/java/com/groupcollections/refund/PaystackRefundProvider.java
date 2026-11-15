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
      return map(response, refund, false);
    } catch (RestClientResponseException failure) {
      RefundResult.PendingReason reason = retryReason(failure);
      return reason == null
          ? RefundResult.failed("PROVIDER_REJECTED")
          : RefundResult.pending(null, reason);
    } catch (RestClientException failure) {
      return RefundResult.pending(null, RefundResult.PendingReason.TIMEOUT);
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
        return RefundResult.pending(null, RefundResult.PendingReason.UNKNOWN_STATUS);
      }
      return map(response, refund, true);
    } catch (RestClientResponseException failure) {
      return RefundResult.pending(refund.providerReference(), verifyReason(failure));
    } catch (RestClientException failure) {
      return RefundResult.pending(refund.providerReference(), RefundResult.PendingReason.TIMEOUT);
    }
  }

  /**
   * Paystack returns 408 for a request it may still have accepted and 409 when an earlier attempt
   * already exists, so both are treated like a timeout rather than a rejection. 429 and 5xx are
   * retryable for the same reason: the refund may have landed.
   */
  private static RefundResult.PendingReason retryReason(RestClientResponseException failure) {
    HttpStatusCode status = failure.getStatusCode();
    if (status.value() == 408) {
      return RefundResult.PendingReason.TIMEOUT;
    }
    if (status.value() == 409) {
      return RefundResult.PendingReason.CONFLICT;
    }
    if (status.value() == 429) {
      return RefundResult.PendingReason.RATE_LIMITED;
    }
    return status.is5xxServerError() ? RefundResult.PendingReason.UNKNOWN_STATUS : null;
  }

  private static RefundResult.PendingReason verifyReason(RestClientResponseException failure) {
    HttpStatusCode status = failure.getStatusCode();
    if (status.value() == 408) {
      return RefundResult.PendingReason.TIMEOUT;
    }
    if (status.value() == 409) {
      return RefundResult.PendingReason.CONFLICT;
    }
    if (status.value() == 429) {
      return RefundResult.PendingReason.RATE_LIMITED;
    }
    return RefundResult.PendingReason.UNKNOWN_STATUS;
  }

  private static RefundResult map(JsonNode response, Refund refund, boolean verification) {
    if (response == null || !response.isObject()) {
      return RefundResult.pending(null, RefundResult.PendingReason.UNKNOWN_STATUS);
    }
    if (!response.path("status").asBoolean(false)) {
      return verification
          ? RefundResult.pending(null, RefundResult.PendingReason.UNKNOWN_STATUS)
          : RefundResult.failed("PROVIDER_REJECTED");
    }
    JsonNode data = response.path("data");
    if (data.isArray()) {
      return mapRefundList(data, refund);
    }
    if (!data.isObject()) {
      return RefundResult.pending(null, RefundResult.PendingReason.UNKNOWN_STATUS);
    }
    return mapRefund(data, refund);
  }

  /**
   * {@code GET /refund?transaction=} returns an array. Select the latest entry that belongs to the
   * transaction and matches the refunded amount and currency; anything else is unknowable.
   */
  private static RefundResult mapRefundList(JsonNode data, Refund refund) {
    JsonNode selected = null;
    String selectedAt = "";
    boolean amountMismatch = false;
    boolean currencyMismatch = false;
    for (JsonNode entry : data) {
      if (!entry.isObject() || !transactionMatches(entry, refund)) {
        continue;
      }
      if (!amountMatches(entry, refund)) {
        amountMismatch = true;
        continue;
      }
      if (!currencyMatches(entry, refund)) {
        currencyMismatch = true;
        continue;
      }
      String createdAt = entry.path("created_at").asText("");
      if (selected == null || createdAt.compareTo(selectedAt) >= 0) {
        selected = entry;
        selectedAt = createdAt;
      }
    }
    if (selected != null) {
      return mapRefund(selected, refund);
    }
    if (amountMismatch) {
      return RefundResult.pending(null, RefundResult.PendingReason.AMOUNT_MISMATCH);
    }
    if (currencyMismatch) {
      return RefundResult.pending(null, RefundResult.PendingReason.CURRENCY_MISMATCH);
    }
    return RefundResult.pending(null, RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  private static boolean transactionMatches(JsonNode entry, Refund refund) {
    String reference = entry.path("transaction").path("reference").asText("");
    return reference.isBlank() || reference.equals(refund.providerTransactionReference());
  }

  private static RefundResult mapRefund(JsonNode data, Refund refund) {
    String reference =
        data.hasNonNull("id")
            ? data.get("id").asText()
            : data.hasNonNull("reference") ? data.get("reference").asText() : null;
    if (!amountMatches(data, refund)) {
      return RefundResult.pending(reference, RefundResult.PendingReason.AMOUNT_MISMATCH);
    }
    if (!currencyMatches(data, refund)) {
      return RefundResult.pending(reference, RefundResult.PendingReason.CURRENCY_MISMATCH);
    }
    String status = data.path("status").asText("");
    return switch (status) {
      case "processed", "success" -> RefundResult.success(reference);
      case "failed", "reversed" -> RefundResult.failed("PROVIDER_REFUND_FAILED");
      default -> RefundResult.pending(reference, RefundResult.PendingReason.UNKNOWN_STATUS);
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
