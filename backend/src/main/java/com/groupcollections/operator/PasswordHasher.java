package com.groupcollections.operator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 password hashing, as ADR 0017 permits when Argon2 is unavailable. */
public final class PasswordHasher {

  public static final int DEFAULT_ITERATIONS = 600_000;
  public static final int MAX_ITERATIONS = 2_000_000;
  public static final int MIN_PASSWORD_LENGTH = 12;
  public static final int MAX_PASSWORD_LENGTH = 1_024;

  private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String PREFIX = "pbkdf2-sha256";
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;

  private final int iterations;
  private final SecureRandom random;

  public PasswordHasher() {
    this(DEFAULT_ITERATIONS, new SecureRandom());
  }

  public PasswordHasher(int iterations, SecureRandom random) {
    if (iterations < DEFAULT_ITERATIONS || iterations > MAX_ITERATIONS) {
      throw new IllegalArgumentException(
          "password hashing iterations must be between "
              + DEFAULT_ITERATIONS
              + " and "
              + MAX_ITERATIONS);
    }
    this.iterations = iterations;
    this.random = random;
  }

  public String hash(String password) {
    requirePolicy(password);
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] hash = derive(password, salt, iterations);
    return PREFIX
        + "$"
        + iterations
        + "$"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
        + "$"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
  }

  public boolean matches(String password, String encoded) {
    if (password == null || encoded == null || !policyLength(password.length())) {
      return false;
    }
    String[] parts = encoded.split("\\$", -1);
    if (parts.length != 4 || !PREFIX.equals(parts[0])) {
      return false;
    }
    int storedIterations;
    try {
      storedIterations = Integer.parseInt(parts[1]);
    } catch (NumberFormatException failure) {
      return false;
    }
    if (storedIterations < DEFAULT_ITERATIONS || storedIterations > MAX_ITERATIONS) {
      return false;
    }
    byte[] salt;
    byte[] expected;
    try {
      salt = Base64.getUrlDecoder().decode(parts[2]);
      expected = Base64.getUrlDecoder().decode(parts[3]);
    } catch (IllegalArgumentException failure) {
      return false;
    }
    if (salt.length != SALT_BYTES || expected.length != HASH_BITS / 8) {
      return false;
    }
    byte[] actual = derive(password, salt, storedIterations);
    return MessageDigest.isEqual(actual, expected);
  }

  private static void requirePolicy(String password) {
    if (password == null || !policyLength(password.length())) {
      throw new IllegalArgumentException(
          "password must be between "
              + MIN_PASSWORD_LENGTH
              + " and "
              + MAX_PASSWORD_LENGTH
              + " characters");
    }
  }

  private static boolean policyLength(int length) {
    return length >= MIN_PASSWORD_LENGTH && length <= MAX_PASSWORD_LENGTH;
  }

  private static byte[] derive(String password, byte[] salt, int iterations) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
    try {
      return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException failure) {
      throw new IllegalStateException("PBKDF2 is unavailable", failure);
    } finally {
      spec.clearPassword();
    }
  }

  static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }
}
