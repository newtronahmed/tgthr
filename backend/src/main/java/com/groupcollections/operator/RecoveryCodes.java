package com.groupcollections.operator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Single-use recovery codes. Codes are high-entropy random values, so they are stored with SHA-256
 * rather than a password KDF; regenerating a set invalidates the old one in the operator service.
 * {@link #matches(String, String)} does not consume a code; the caller marks the hash used.
 */
public final class RecoveryCodes {

  public static final int COUNT = 10;
  private static final int CODE_BYTES = 10;

  public record Generated(List<String> plaintext, List<String> hashes) {
    public Generated {
      plaintext = List.copyOf(plaintext);
      hashes = List.copyOf(hashes);
    }

    @Override
    public String toString() {
      return "Generated[plaintext=[REDACTED], hashCount=" + hashes.size() + "]";
    }
  }

  private RecoveryCodes() {}

  public static Generated generate(SecureRandom random) {
    List<String> plaintext = new ArrayList<>(COUNT);
    List<String> hashes = new ArrayList<>(COUNT);
    for (int index = 0; index < COUNT; index++) {
      byte[] bytes = new byte[CODE_BYTES];
      random.nextBytes(bytes);
      String code = Base32.encode(bytes);
      plaintext.add(code);
      hashes.add(sha256(code));
    }
    return new Generated(plaintext, hashes);
  }

  public static boolean matches(String code, String hash) {
    if (code == null || hash == null) {
      return false;
    }
    String normalized = code.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(
        sha256(normalized).getBytes(StandardCharsets.US_ASCII),
        hash.getBytes(StandardCharsets.US_ASCII));
  }

  static String sha256(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(PasswordHasher.sha256(value));
  }
}
