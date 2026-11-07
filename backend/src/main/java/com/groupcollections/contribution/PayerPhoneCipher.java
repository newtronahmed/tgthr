package com.groupcollections.contribution;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the payer's phone number with AES-GCM and builds a keyed lookup hash, as required by the
 * spec's security section. The configured master key is split into separate encryption and lookup
 * keys so the same key material is never reused across primitives.
 */
public final class PayerPhoneCipher {

  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final String MAC = "HmacSHA256";
  private static final int MASTER_KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec lookupKey;
  private final SecureRandom random;

  public PayerPhoneCipher(String base64MasterKey, SecureRandom random) {
    this.random = random;
    byte[] master = Base64.getDecoder().decode(base64MasterKey);
    if (master.length != MASTER_KEY_BYTES) {
      throw new IllegalArgumentException("payer phone key must decode to 32 bytes");
    }
    encryptionKey = new SecretKeySpec(derive(master, "encryption"), "AES");
    lookupKey = new SecretKeySpec(derive(master, "lookup"), MAC);
  }

  public byte[] encrypt(String phone) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
      byte[] sealed = cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[iv.length + sealed.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(sealed, 0, result, iv.length, sealed.length);
      return result;
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not encrypt the payer phone", failure);
    }
  }

  public byte[] hash(String phone) {
    try {
      Mac mac = Mac.getInstance(MAC);
      mac.init(lookupKey);
      return mac.doFinal(phone.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not hash the payer phone", failure);
    }
  }

  private static byte[] derive(byte[] key, String label) {
    try {
      Mac mac = Mac.getInstance(MAC);
      mac.init(new SecretKeySpec(key, MAC));
      return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not derive a payer phone key", failure);
    }
  }
}
