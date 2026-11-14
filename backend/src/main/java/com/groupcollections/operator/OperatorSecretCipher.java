package com.groupcollections.operator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM encryption for TOTP secrets at rest. The key is independent of the payer-phone key. */
public final class OperatorSecretCipher {

  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random;

  public OperatorSecretCipher(String base64Key, SecureRandom random) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalArgumentException("operator TOTP encryption key is required");
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("operator TOTP encryption key is not valid base64");
    }
    if (decoded.length != KEY_BYTES) {
      throw new IllegalArgumentException("operator TOTP encryption key must decode to 32 bytes");
    }
    this.key = new SecretKeySpec(decoded, "AES");
    this.random = random;
  }

  public byte[] encrypt(String secret) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[iv.length + sealed.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(sealed, 0, result, iv.length, sealed.length);
      return result;
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not encrypt the operator TOTP secret", failure);
    }
  }

  public String decrypt(byte[] sealed) {
    if (sealed == null || sealed.length <= IV_BYTES) {
      throw new IllegalStateException("could not decrypt the operator TOTP secret");
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      System.arraycopy(sealed, 0, iv, 0, IV_BYTES);
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plain = cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not decrypt the operator TOTP secret", failure);
    }
  }
}
