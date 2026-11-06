package com.groupcollections.payments;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter for the Paystack charge API (ADR 0006). */
public final class PaystackHttpClient implements PaystackClient {

  private static final String PLATFORM_EMAIL = "payments@groupcollections.app";
  private static final String CURRENCY = "GHS";
  private static final String DECLINED_STATUS = "failed";

  private final RestClient restClient;

  public PaystackHttpClient(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public boolean initiateCharge(
      UUID reference, long totalPesewas, String momoNumber, Network network) {
    ChargeRequest request =
        new ChargeRequest(
            PLATFORM_EMAIL,
            totalPesewas,
            CURRENCY,
            reference.toString(),
            new MobileMoney(momoNumber, network.providerCode()));
    try {
      ChargeResponse response =
          restClient.post().uri("/charge").body(request).retrieve().body(ChargeResponse.class);
      return response != null
          && response.status()
          && response.data() != null
          && !DECLINED_STATUS.equals(response.data().status());
    } catch (RestClientException failure) {
      return false;
    }
  }

  @Override
  public PaymentVerification verify(String providerReference) {
    try {
      VerifyResponse response =
          restClient
              .get()
              .uri("/transaction/verify/{reference}", providerReference)
              .retrieve()
              .body(VerifyResponse.class);
      if (response == null || !response.status() || response.data() == null) {
        return PaymentVerification.UNKNOWN;
      }
      String status = response.data().status();
      if (status == null) {
        return PaymentVerification.UNKNOWN;
      }
      return switch (status) {
        case "success" -> PaymentVerification.PAID;
        case "failed", "reversed" -> PaymentVerification.FAILED;
        case "abandoned" -> PaymentVerification.NO_PAYMENT;
        default -> PaymentVerification.UNKNOWN;
      };
    } catch (RestClientException failure) {
      return PaymentVerification.UNKNOWN;
    }
  }

  record ChargeRequest(
      String email,
      long amount,
      String currency,
      String reference,
      @JsonProperty("mobile_money") MobileMoney mobileMoney) {}

  record MobileMoney(String phone, String provider) {}

  record ChargeResponse(boolean status, Data data) {
    record Data(String reference, String status) {}
  }

  record VerifyResponse(boolean status, VerifyData data) {
    record VerifyData(String status) {}
  }
}
