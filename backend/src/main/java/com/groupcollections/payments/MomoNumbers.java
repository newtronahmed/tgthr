package com.groupcollections.payments;

/** Normalizes stored Ghanaian MoMo numbers to the local form payouts/providers expect. */
public final class MomoNumbers {

  private MomoNumbers() {}

  /** Converts E.164 (`+233XXXXXXXXX`) or a local number (`0XXXXXXXXX`) to the local form. */
  public static String local(String stored) {
    if (stored == null) {
      throw new IllegalArgumentException("missing mobile money number");
    }
    String value = stored.trim();
    if (value.startsWith("+233") && value.length() == 13) {
      return "0" + value.substring(4);
    }
    if (value.startsWith("0") && value.length() == 10) {
      return value;
    }
    throw new IllegalArgumentException("not a Ghanaian mobile number");
  }
}
