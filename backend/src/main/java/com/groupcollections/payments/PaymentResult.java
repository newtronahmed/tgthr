package com.groupcollections.payments;

import java.time.Instant;

/** The internal result of a partner callback, independent of Paystack's payload shape. */
public record PaymentResult(
    String providerReference, boolean success, long amountPesewas, Instant occurredAt) {}
