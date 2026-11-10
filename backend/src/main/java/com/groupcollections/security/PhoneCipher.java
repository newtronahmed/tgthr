package com.groupcollections.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts phone numbers and builds keyed lookup hashes. The configured master key is split into
 * separate encryption, lookup and OTP keys so the same key material is never reused across
 * primitives.
 */
public final class PhoneCipher {

  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final String MAC = "HmacSHA256";
  private static final int MASTER_KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encryptionKey;
  private final SecretKeySpec lookupKey;
  private final SecretKeySpec otpKey;
  private final SecureRandom random;

  public PhoneCipher(String base64MasterKey, SecureRandom random) {
    this.random = random;
    byte[] master = Base64.getDecoder().decode(base64MasterKey);
    if (master.length != MASTER_KEY_BYTES) {
      throw new IllegalArgumentException("phone key must decode to 32 bytes");
    }
    encryptionKey = new SecretKeySpec(derive(master, "encryption"), "AES");
    lookupKey = new SecretKeySpec(derive(master, "lookup"), MAC);
    otpKey = new SecretKeySpec(derive(master, "otp"), MAC);
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
      throw new IllegalStateException("could not encrypt the phone", failure);
    }
  }

  public String decrypt(byte[] sealed) {
    if (sealed == null || sealed.length <= IV_BYTES) {
      throw new IllegalStateException("could not decrypt the phone");
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      System.arraycopy(sealed, 0, iv, 0, IV_BYTES);
      Cipher cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plain = cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not decrypt the phone", failure);
    }
  }

  public byte[] hash(String phone) {
    return mac(lookupKey, phone, "could not hash the phone");
  }

  public byte[] hashOtpCode(String code) {
    return mac(otpKey, code, "could not hash the OTP code");
  }

  private static byte[] mac(SecretKeySpec key, String value, String message) {
    try {
      Mac mac = Mac.getInstance(MAC);
      mac.init(key);
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(message, failure);
    }
  }

  private static byte[] derive(byte[] key, String label) {
    try {
      Mac mac = Mac.getInstance(MAC);
      mac.init(new SecretKeySpec(key, MAC));
      return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("could not derive a phone key", failure);
    }
  }
}
