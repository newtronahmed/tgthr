package com.groupcollections.identity;

import java.util.regex.Pattern;

/** Normalizes a Ghanaian mobile number to E.164, the only form the identity module stores. */
public final class PhoneNormalizer {

  private static final Pattern LOCAL = Pattern.compile("^0[0-9]{9}$");
  private static final Pattern E164 = Pattern.compile("^\\+233[0-9]{9}$");

  private PhoneNormalizer() {}

  public static String normalize(String raw) {
    if (raw == null) {
      throw IdentityException.invalidPhone();
    }
    String value = raw.trim().replace(" ", "").replace("-", "");
    if (LOCAL.matcher(value).matches()) {
      return "+233" + value.substring(1);
    }
    if (E164.matcher(value).matches()) {
      return value;
    }
    throw IdentityException.invalidPhone();
  }
}
