package com.groupcollections.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.groupcollections.payments.Network;
import com.groupcollections.security.PhoneCipher;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Paystack transfer adapter. It creates the MoMo recipient, initiates one transfer with the payout
 * ID as the idempotency reference, and verifies an existing transfer if Paystack reports a
 * duplicate reference. Tests use the fake server; no live provider is reachable from the suite.
 */
@Component
@ConditionalOnProperty(name = "collections.payout.transfer-provider", havingValue = "paystack")
public class PaystackPayoutTransferProvider implements PayoutTransferProvider {

  private final RestClient restClient;
  private final PayoutRepository payouts;
  private final PhoneCipher phones;

  public PaystackPayoutTransferProvider(
      RestClient restClient, PayoutRepository payouts, PhoneCipher phones) {
    this.restClient = restClient;
    this.payouts = payouts;
    this.phones = phones;
  }

  @Override
  public TransferResult transfer(Payout payout) {
    String recipient;
    try {
      PayoutRepository.PayoutDestination destination =
          payouts.findDestination(payout.goalId()).orElse(null);
      if (destination == null || destination.phoneCiphertext() == null) {
        return failed("DESTINATION_MISSING");
      }
      String phone = phones.decrypt(destination.phoneCiphertext());
      String bankCode = Network.valueOf(destination.network()).transferCode();
      recipient = createRecipient(phone, bankCode, destination.accountName());
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return failed("DESTINATION_INVALID");
    } catch (RestClientResponseException failure) {
      return failure.getStatusCode().is4xxClientError()
          ? failed("PROVIDER_RECIPIENT_REJECTED")
          : unknown();
    } catch (RestClientException failure) {
      return unknown();
    }
    if (recipient == null) {
      return failed("PROVIDER_RECIPIENT_REJECTED");
    }

    String reference = "tgthr-payout-" + payout.id();
    try {
      JsonNode response =
          restClient
              .post()
              .uri("/transfer")
              .body(
                  Map.of(
                      "source",
                      "balance",
                      "amount",
                      payout.netPesewas(),
                      "recipient",
                      recipient,
                      "reference",
                      reference,
                      "reason",
                      "Payout " + payout.id(),
                      "currency",
                      "GHS"))
              .retrieve()
              .body(JsonNode.class);
      return map(response);
    } catch (RestClientResponseException failure) {
      if (failure.getStatusCode().value() == 400 && duplicate(failure.getResponseBodyAsString())) {
        return verify(reference);
      }
      return failure.getStatusCode().is4xxClientError() ? failed("PROVIDER_REJECTED") : unknown();
    } catch (RestClientException failure) {
      return unknown();
    }
  }

  private String createRecipient(String phone, String bankCode, String name) {
    JsonNode response =
        restClient
            .post()
            .uri("/transferrecipient")
            .body(
                Map.of(
                    "type",
                    "mobile_money",
                    "name",
                    name == null || name.isBlank() ? "Beneficiary" : name,
                    "account_number",
                    phone,
                    "bank_code",
                    bankCode,
                    "currency",
                    "GHS"))
            .retrieve()
            .body(JsonNode.class);
    if (response == null || !response.path("status").asBoolean(false)) {
      return null;
    }
    String code = response.path("data").path("recipient_code").asText(null);
    return code == null || code.isBlank() ? null : code;
  }

  private TransferResult verify(String reference) {
    try {
      JsonNode response =
          restClient
              .get()
              .uri("/transfer/verify/{reference}", reference)
              .retrieve()
              .body(JsonNode.class);
      return map(response);
    } catch (RestClientException failure) {
      return unknown();
    }
  }

  private static TransferResult map(JsonNode response) {
    if (response == null || !response.path("status").asBoolean(false)) {
      return failed("PROVIDER_REJECTED");
    }
    JsonNode data = response.path("data");
    String status = data.path("status").asText("");
    String code = data.path("transfer_code").asText(null);
    return switch (status) {
      case "success" ->
          code == null || code.isBlank()
              ? unknown()
              : new TransferResult(TransferResult.Status.SUCCESS, code, null);
      case "failed", "reversed" -> failed("PROVIDER_FAILED");
      case "otp", "pending" -> unknown();
      default -> unknown();
    };
  }

  private static boolean duplicate(String body) {
    String lower = body == null ? "" : body.toLowerCase();
    return lower.contains("already") || lower.contains("duplicate");
  }

  private static TransferResult failed(String failureCode) {
    return new TransferResult(TransferResult.Status.FAILED, null, failureCode);
  }

  private static TransferResult unknown() {
    return new TransferResult(TransferResult.Status.UNKNOWN, null, null);
  }
}
