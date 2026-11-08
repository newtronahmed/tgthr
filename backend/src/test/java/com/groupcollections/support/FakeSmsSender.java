package com.groupcollections.support;

import com.groupcollections.identity.SmsSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Records SMS messages instead of calling a provider. */
public final class FakeSmsSender implements SmsSender {

  private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
  private static final List<Sent> SENT = new CopyOnWriteArrayList<>();

  public record Sent(String phone, String message) {}

  @Override
  public void send(String phoneE164, String message) {
    SENT.add(new Sent(phoneE164, message));
  }

  public static void reset() {
    SENT.clear();
  }

  public static List<Sent> sent() {
    return List.copyOf(SENT);
  }

  public static String latestCode() {
    if (SENT.isEmpty()) {
      throw new IllegalStateException("no SMS was sent");
    }
    Matcher matcher = CODE.matcher(SENT.get(SENT.size() - 1).message());
    if (!matcher.find()) {
      throw new IllegalStateException("the SMS has no six-digit code");
    }
    return matcher.group(1);
  }
}
