package com.groupcollections.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.groupcollections.payments.MomoNumbers;
import com.groupcollections.payments.Network;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Paystack MoMo account resolution. The account is not found (4xx) when Paystack rejects the
 * number/network; provider failures are 503 so the organizer can retry.
 */
@Component
@ConditionalOnProperty(name = "collections.payments.account-resolver", havingValue = "paystack")
public class PaystackAccountResolver implements AccountResolver {

  private final RestClient restClient;

  public PaystackAccountResolver(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public String resolve(String momoNumber, Network network) {
    if (network == null) {
      throw OrganizerGoalException.destinationUnavailable();
    }
    String accountNumber = MomoNumbers.local(momoNumber);
    try {
      JsonNode response =
          restClient
              .get()
              .uri(
                  "/bank/resolve?account_number={account}&bank_code={bank}",
                  accountNumber,
                  network.transferCode())
              .retrieve()
              .body(JsonNode.class);
      if (response == null || !response.path("status").asBoolean(false)) {
        return null;
      }
      String name = response.path("data").path("account_name").asText(null);
      return name == null || name.isBlank() ? null : name;
    } catch (RestClientResponseException failure) {
      if (failure.getStatusCode().is4xxClientError()) {
        return null;
      }
      throw OrganizerGoalException.destinationUnavailable();
    } catch (RestClientException failure) {
      throw OrganizerGoalException.destinationUnavailable();
    }
  }
}
