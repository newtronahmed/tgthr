package com.groupcollections.operator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP with SHA-1, six digits and a 30-second step. The caller stores the last consumed
 * step per operator so a code cannot be replayed (ADR 0017).
 */
public final class Totp {

  public static final int DIGITS = 6;
  public static final long STEP_SECONDS = 30;
  public static final int WINDOW_STEPS = 1;

  private static final int MODULUS = 1_000_000;
  private static final String HMAC_SHA1 = "HmacSHA1";

  private Totp() {}

  public static long stepAt(Instant time) {
    return Math.floorDiv(Objects.requireNonNull(time).getEpochSecond(), STEP_SECONDS);
  }

  public static String code(byte[] secret, Instant time) {
    return codeForStep(secret, stepAt(time));
  }

  /**
   * Verifies a six-digit code at the current step and the adjacent steps. Returns the matched step
   * only when it is newer than {@code lastConsumedStep}; returns empty for malformed, expired,
   * future-outside-window or replayed codes.
   */
  public static OptionalLong verify(
      byte[] secret, String code, Instant time, long lastConsumedStep) {
    if (secret == null || secret.length == 0 || !isSixDigits(code)) {
      return OptionalLong.empty();
    }
    long currentStep = stepAt(time);
    for (long offset = -WINDOW_STEPS; offset <= WINDOW_STEPS; offset++) {
      long step = currentStep + offset;
      if (step < 0) {
        continue;
      }
      String candidate = codeForStep(secret, step);
      if (MessageDigest.isEqual(
              candidate.getBytes(StandardCharsets.US_ASCII),
              code.getBytes(StandardCharsets.US_ASCII))
          && step > lastConsumedStep) {
        return OptionalLong.of(step);
      }
    }
    return OptionalLong.empty();
  }

  static String codeForStep(byte[] secret, long step) {
    if (secret == null || secret.length == 0) {
      throw new IllegalArgumentException("a TOTP secret is required");
    }
    byte[] counter = new byte[8];
    long value = step;
    for (int index = counter.length - 1; index >= 0; index--) {
      counter[index] = (byte) (value & 0xff);
      value >>>= 8;
    }
    try {
      Mac mac = Mac.getInstance(HMAC_SHA1);
      mac.init(new SecretKeySpec(secret, HMAC_SHA1));
      byte[] hash = mac.doFinal(counter);
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % MODULUS);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("HMAC-SHA1 is unavailable", failure);
    }
  }

  private static boolean isSixDigits(String code) {
    if (code == null || code.length() != DIGITS) {
      return false;
    }
    for (int index = 0; index < code.length(); index++) {
      char character = code.charAt(index);
      if (character < '0' || character > '9') {
        return false;
      }
    }
    return true;
  }
}
