package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundReferencesTest {

  /** Paystack accepts 8–64 characters of letters, digits, {@code _} and {@code -}. */
  @Test
  void clientReferenceIsDeterministicAndAcceptedByTheProvider() {
    UUID refundId = UUID.fromString("d1000000-0000-0000-0000-000000000001");

    String reference = RefundReferences.clientReference(refundId);

    assertThat(reference).isEqualTo("tgthr-refund-d1000000-0000-0000-0000-000000000001");
    assertThat(RefundReferences.clientReference(refundId)).isEqualTo(reference);
    assertThat(reference).hasSizeBetween(8, 64).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void distinctRefundsNeverShareAClientReference() {
    assertThat(RefundReferences.clientReference(UUID.randomUUID()))
        .isNotEqualTo(RefundReferences.clientReference(UUID.randomUUID()));
  }
}
