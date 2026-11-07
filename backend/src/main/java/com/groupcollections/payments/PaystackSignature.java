package com.groupcollections.payments;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies Paystack's HMAC-SHA512 webhook signature over the raw request body (ADR 0006). */
public final class PaystackSignature {

  private static final String ALGORITHM = "HmacSHA512";

  private final SecretKeySpec key;

  public PaystackSignature(String secretKey) {
    this.key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  public boolean isValid(String rawBody, String providedHex) {
    return isValid(rawBody.getBytes(StandardCharsets.UTF_8), providedHex);
  }

  public boolean isValid(byte[] rawBody, String providedHex) {
    if (providedHex == null || providedHex.isBlank()) {
      return false;
    }
    byte[] provided;
    try {
      provided = HexFormat.of().parseHex(providedHex);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    return MessageDigest.isEqual(sign(rawBody), provided);
  }

  private byte[] sign(byte[] rawBody) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(rawBody);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not verify the Paystack signature", failure);
    }
  }
}
