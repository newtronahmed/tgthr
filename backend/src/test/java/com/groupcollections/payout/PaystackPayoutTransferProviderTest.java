package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupcollections.payout.PayoutTransferProvider.TransferResult;
import com.groupcollections.security.PhoneCipher;
import com.groupcollections.support.FakePaystackServer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class PaystackPayoutTransferProviderTest {

  private static final String PHONE_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final String RECIPIENT_PHONE = "0551234987";

  private final ObjectMapper mapper = new ObjectMapper();
  private FakePaystackServer fake;
  private PayoutRepository payouts;
  private PhoneCipher phones;
  private PaystackPayoutTransferProvider provider;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakePaystackServer("test-secret");
    payouts = mock(PayoutRepository.class);
    phones = new PhoneCipher(PHONE_KEY, new SecureRandom());
    when(payouts.findDestination(any()))
        .thenReturn(
            Optional.of(
                new PayoutRepository.PayoutDestination(
                    phones.encrypt(RECIPIENT_PHONE), "MTN", "Ama Mensah")));
    RestClient restClient =
        RestClient.builder()
            .baseUrl(fake.baseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-secret")
            .build();
    provider = new PaystackPayoutTransferProvider(restClient, payouts, phones);
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  @Test
  void successfulTransferUsesTheBeneficiaryNetAndPayoutReference() throws Exception {
    Payout payout = payout();

    TransferResult result = provider.transfer(payout);

    assertThat(result.status()).isEqualTo(TransferResult.Status.SUCCESS);
    assertThat(result.providerReference()).isEqualTo("TRF_FAKE_OK");
    JsonNode body = mapper.readTree(fake.transferBodies().get(0));
    assertThat(body.path("amount").asLong()).isEqualTo(payout.netPesewas());
    assertThat(body.path("reference").asText()).isEqualTo("tgthr-payout-" + payout.id());
    assertThat(body.path("recipient").asText()).isEqualTo("RCP_FAKE");
    assertThat(body.path("currency").asText()).isEqualTo("GHS");
  }

  @Test
  void failedProviderStatusIsTerminal() {
    fake.transferScenario("FAILED");

    TransferResult result = provider.transfer(payout());

    assertThat(result.status()).isEqualTo(TransferResult.Status.FAILED);
    assertThat(result.failureCode()).isEqualTo("PROVIDER_FAILED");
  }

  @Test
  void otpIsUnknownRatherThanRetried() {
    fake.transferScenario("OTP");

    assertThat(provider.transfer(payout()).status()).isEqualTo(TransferResult.Status.UNKNOWN);
  }

  @Test
  void providerFailureIsUnknown() {
    fake.transferScenario("UNKNOWN");

    assertThat(provider.transfer(payout()).status()).isEqualTo(TransferResult.Status.UNKNOWN);
  }

  @Test
  void duplicateReferenceVerifiesTheExistingTransfer() {
    fake.transferScenario("DUPLICATE");

    TransferResult result = provider.transfer(payout());

    assertThat(result.status()).isEqualTo(TransferResult.Status.SUCCESS);
    assertThat(result.providerReference()).isEqualTo("TRF_FAKE_OK");
  }

  private Payout payout() {
    return new Payout(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1_000,
        100,
        900,
        "GHS",
        "EXECUTING",
        "payout-" + UUID.randomUUID(),
        null,
        Instant.now());
  }
}
