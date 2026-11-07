package com.groupcollections.payments;

import java.util.UUID;

/** The partner operations FEAT-001 needs. Tests use the in-process fake, never the real service. */
public interface PaystackClient {

  /**
   * Requests the mobile money prompt. Returns true when the partner accepted the charge request.
   */
  boolean initiateCharge(UUID reference, long totalPesewas, String momoNumber, Network network);

  /** Looks up the final status of a charge; UNKNOWN means the lookup failed and must be retried. */
  PaymentStatus verify(String providerReference);
}
