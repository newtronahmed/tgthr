package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupcollections.refund.RefundProvider.RefundResult;
import com.groupcollections.support.FakePaystackServer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class PaystackRefundProviderTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private FakePaystackServer fake;
  private PaystackRefundProvider provider;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakePaystackServer("test-secret");
    RestClient restClient =
        RestClient.builder()
            .baseUrl(fake.baseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-secret")
            .build();
    provider = new PaystackRefundProvider(restClient);
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  @Test
  void successfulRefundPostsTheTotalAndReturnsTheProviderReference() throws Exception {
    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.SUCCESS);
    assertThat(result.providerReference()).startsWith("refund-");
    assertThat(result.failureCode()).isNull();
    JsonNode body = mapper.readTree(fake.refundBodies().get(0));
    assertThat(body.path("transaction").asText()).isEqualTo("txn-123");
    assertThat(body.path("amount").asLong()).isEqualTo(10_500);
    assertThat(body.path("currency").asText()).isEqualTo("GHS");
  }

  @Test
  void missingProviderTransactionReferenceIsFailedWithoutCallingTheProvider() {
    RefundResult result = provider.refund(refund(null, null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.FAILED);
    assertThat(result.failureCode()).isEqualTo("PROVIDER_REFERENCE_MISSING");
    assertThat(fake.refundBodies()).isEmpty();
  }

  @Test
  void requestTimeoutIsPending() {
    fake.refundScenario("REQUEST_TIMEOUT");

    assertThat(provider.refund(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void alreadyExistingRefundIsPendingRatherThanRejected() {
    fake.refundScenario("ALREADY_EXISTS");

    assertThat(provider.refund(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void rateLimitedRefundIsPending() {
    fake.refundScenario("RATE_LIMITED");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.failureCode()).isNull();
  }

  @Test
  void definitiveRejectionIsFailed() {
    fake.refundScenario("REQUEST_REJECTED");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.FAILED);
    assertThat(result.failureCode()).isEqualTo("PROVIDER_REJECTED");
  }

  @Test
  void serverErrorIsPending() {
    fake.refundScenario("SERVER_ERROR");

    assertThat(provider.refund(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void providerFailedStatusIsTerminal() {
    fake.refundScenario("FAILED");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.FAILED);
    assertThat(result.failureCode()).isEqualTo("PROVIDER_REFUND_FAILED");
  }

  @Test
  void amountMismatchIsPending() {
    fake.refundScenario("AMOUNT_MISMATCH");

    assertThat(provider.refund(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void currencyMismatchIsPending() {
    fake.refundScenario("CURRENCY_MISMATCH");

    assertThat(provider.refund(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void missingRefundedAmountIsPending() {
    fake.refundScenario("MISSING_AMOUNT");

    assertThat(provider.refund(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void verifyByProviderReferenceAcceptsTheExactAmount() {
    RefundResult requested = provider.refund(refund("txn-123", null));

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.SUCCESS);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  @Test
  void verifyByTransactionWorksWhenTheRequestOutcomeWasUnknown() {
    fake.refundScenario("SERVER_ERROR");
    RefundResult requested = provider.refund(refund("txn-123", null));
    assertThat(requested.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(requested.providerReference()).isNull();

    fake.refundScenario("SUCCESS");
    RefundResult verified = provider.verify(refund("txn-123", null));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.SUCCESS);
  }

  @Test
  void verifySelectsTheMatchingEntryFromTheRefundList() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_MULTIPLE");

    RefundResult verified = provider.verify(refund("txn-123", null));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.SUCCESS);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  @Test
  void verifyUsesTheLatestMatchingEntry() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_MULTIPLE_MATCHING");

    RefundResult verified = provider.verify(refund("txn-123", null));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.SUCCESS);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  @Test
  void verifyWithAnEmptyRefundListStaysPending() {
    provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_EMPTY");

    RefundResult verified = provider.verify(refund("txn-123", null));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isNull();
  }

  @Test
  void verifyWithoutAMatchingAmountStaysPending() {
    provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_NO_MATCH");

    assertThat(provider.verify(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void verifyWithoutAMatchingTransactionStaysPending() {
    provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_WRONG_TRANSACTION");

    assertThat(provider.verify(refund("txn-123", null)).status())
        .isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void verifyWithAnUnrecognisedProviderStatusStaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("UNKNOWN_STATUS");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  @Test
  void verifyWithA200ButFalseStatusStaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_200_FALSE");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isNull();
  }

  @Test
  void verifyWithA404StaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_404");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  @Test
  void verifyRejectsAMismatchedAmount() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("AMOUNT_MISMATCH");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  @Test
  void verifyRejectsAMismatchedCurrency() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("CURRENCY_MISMATCH");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
  }

  @Test
  void verifyServerErrorStaysPendingWithTheKnownReference() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("SERVER_ERROR");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
  }

  private static Refund refund(String transactionReference, String providerReference) {
    Instant now = Instant.now();
    return new Refund(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "GOAL_POOL",
        10_000,
        300,
        200,
        10_500,
        "GHS",
        RefundState.PENDING,
        "idem-" + UUID.randomUUID(),
        transactionReference,
        providerReference,
        UUID.randomUUID(),
        "duplicate charge",
        null,
        null,
        null,
        null,
        now,
        now);
  }
}
