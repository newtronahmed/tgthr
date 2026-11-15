package com.groupcollections.refund;

import java.util.regex.Pattern;

/** Rejects operator free text that would copy a payer phone number into the audit trail. */
final class RefundReasons {

  /** Ghana mobile numbers, local or international, with optional spaces or dashes. */
  private static final Pattern PHONE =
      Pattern.compile("(?<!\\d)(?:\\+?233|0)[\\s-]?[2-5][\\s-]?\\d(?:[\\s-]?\\d){7}(?!\\d)");

  private RefundReasons() {}

  static boolean containsPhone(String text) {
    return text != null && PHONE.matcher(text).find();
  }
}
