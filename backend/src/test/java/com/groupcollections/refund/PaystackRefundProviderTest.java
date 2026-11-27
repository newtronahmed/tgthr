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

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.TIMEOUT);
  }

  @Test
  void alreadyExistingRefundIsPendingRatherThanRejected() {
    fake.refundScenario("ALREADY_EXISTS");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.CONFLICT);
  }

  /**
   * Live Paystack answers a duplicate refund with {@code 400} and {@code
   * code=transaction_reversed}, carrying the already-existing refund in {@code
   * data.existing_refunds}. Treating that as a definitive rejection released the reservation and
   * failed the refund while Paystack was in fact refunding the contributor, and threw away the
   * reference needed to verify it. The captured entries carry only {@code id}, {@code reference}
   * and {@code status}, so they cannot be matched by amount.
   */
  @Test
  void duplicateRefundAnsweredWith400TransactionReversedIsPendingAndKeepsTheExistingReference() {
    fake.refundScenario("ALREADY_EXISTS_LIVE");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.CONFLICT);
    assertThat(result.failureCode()).isNull();
    assertThat(result.providerReference()).startsWith("refund-");
  }

  @Test
  void rateLimitedRefundIsPending() {
    fake.refundScenario("RATE_LIMITED");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.failureCode()).isNull();
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.RATE_LIMITED);
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

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
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

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.AMOUNT_MISMATCH);
  }

  @Test
  void currencyMismatchIsPending() {
    fake.refundScenario("CURRENCY_MISMATCH");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.CURRENCY_MISMATCH);
  }

  @Test
  void missingRefundedAmountIsPending() {
    fake.refundScenario("MISSING_AMOUNT");

    RefundResult result = provider.refund(refund("txn-123", null));

    assertThat(result.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(result.pendingReason()).isEqualTo(RefundResult.PendingReason.AMOUNT_MISMATCH);
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
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  @Test
  void verifyWithoutAMatchingAmountStaysPending() {
    provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_NO_MATCH");

    RefundResult verified = provider.verify(refund("txn-123", null));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.AMOUNT_MISMATCH);
  }

  @Test
  void verifyWithoutAMatchingTransactionStaysPending() {
    provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_WRONG_TRANSACTION");

    RefundResult verified = provider.verify(refund("txn-123", null));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  @Test
  void verifyWithAnUnrecognisedProviderStatusStaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("UNKNOWN_STATUS");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  @Test
  void verifyWithA200ButFalseStatusStaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_200_FALSE");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isNull();
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  @Test
  void verifyWithA404StaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("VERIFY_404");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  @Test
  void verifyRejectsAMismatchedAmount() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("AMOUNT_MISMATCH");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.AMOUNT_MISMATCH);
  }

  @Test
  void verifyRejectsAMismatchedCurrency() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("CURRENCY_MISMATCH");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.CURRENCY_MISMATCH);
  }

  @Test
  void verifyServerErrorStaysPendingWithTheKnownReference() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("SERVER_ERROR");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.providerReference()).isEqualTo(requested.providerReference());
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.UNKNOWN_STATUS);
  }

  @Test
  void verifyTimeoutStaysPending() {
    RefundResult requested = provider.refund(refund("txn-123", null));
    fake.refundScenario("REQUEST_TIMEOUT");

    RefundResult verified = provider.verify(refund("txn-123", requested.providerReference()));

    assertThat(verified.status()).isEqualTo(RefundResult.Status.PENDING);
    assertThat(verified.pendingReason()).isEqualTo(RefundResult.PendingReason.TIMEOUT);
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
