package com.groupcollections.goal;

import java.util.Locale;

public final class NameMatcher {

  private NameMatcher() {}

  public static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  public static boolean matches(String resolved, String legal) {
    String left = normalize(resolved);
    String right = normalize(legal);
    return !left.isEmpty() && left.equals(right);
  }
}
