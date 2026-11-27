package com.groupcollections.refund;

import java.util.UUID;

/**
 * The client reference that identifies one refund at the provider.
 *
 * <p>It is derived from the refund's primary key, which is generated before the row is written, so
 * the reference is known before the provider call and reproducible afterwards. Paystack requires
 * 8–64 characters of letters, digits, {@code _} and {@code -}; {@code tgthr-refund-} plus a UUID is
 * 49. The value is persisted in {@code refunds.client_reference} rather than re-derived at each
 * read so a pending refund can be reconciled even if the derivation ever changes.
 */
final class RefundReferences {

  private static final String PREFIX = "tgthr-refund-";

  private RefundReferences() {}

  static String clientReference(UUID refundId) {
    return PREFIX + refundId;
  }
}
