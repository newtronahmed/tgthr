package com.groupcollections.support;

import com.groupcollections.goal.AccountResolver;
import com.groupcollections.goal.OrganizerGoalException;
import com.groupcollections.payments.Network;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves destination names deterministically; tests never call the payment partner. */
public final class FakeAccountResolver implements AccountResolver {

  private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
  private static volatile boolean outage;
  private static volatile boolean unresolvable;

  @Override
  public String resolve(String momoNumber, Network network) {
    if (outage) {
      throw OrganizerGoalException.destinationUnavailable();
    }
    if (unresolvable) {
      return null;
    }
    return NAMES.getOrDefault(local(momoNumber), "Kofi Mensah");
  }

  public static void name(String momoNumber, String accountName) {
    NAMES.put(local(momoNumber), accountName);
  }

  private static String local(String value) {
    return value.startsWith("+233") ? "0" + value.substring(4) : value;
  }

  public static void outage(boolean unavailable) {
    outage = unavailable;
  }

  public static void unresolvable(boolean value) {
    unresolvable = value;
  }

  public static void reset() {
    NAMES.clear();
    outage = false;
    unresolvable = false;
  }
}
