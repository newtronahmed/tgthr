package com.groupcollections.operator;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 base32 without padding, used for TOTP secrets and recovery codes. */
public final class Base32 {

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int[] VALUES = new int[128];

  static {
    java.util.Arrays.fill(VALUES, -1);
    for (int index = 0; index < ALPHABET.length(); index++) {
      VALUES[ALPHABET.charAt(index)] = index;
    }
  }

  private Base32() {}

  public static String encode(byte[] data) {
    if (data == null) {
      throw new IllegalArgumentException("base32 data is required");
    }
    StringBuilder encoded = new StringBuilder((data.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte value : data) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        encoded.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    if (bits > 0) {
      encoded.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
    }
    return encoded.toString();
  }

  public static byte[] decode(String text) {
    if (text == null) {
      throw new IllegalArgumentException("base32 text is required");
    }
    String normalized = text.toUpperCase(Locale.ROOT).replace("=", "");
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(normalized.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (int index = 0; index < normalized.length(); index++) {
      char character = normalized.charAt(index);
      int value = character < VALUES.length ? VALUES[character] : -1;
      if (value < 0) {
        throw new IllegalArgumentException("invalid base32 character");
      }
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        decoded.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    if (bits >= 5 || (buffer & ((1 << bits) - 1)) != 0) {
      throw new IllegalArgumentException("invalid base32 length or padding");
    }
    return decoded.toByteArray();
  }
}
