package com.groupcollections.identity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test-only sender: keeps the latest code so the E2E can read it. Never enable in production. */
@Component
@ConditionalOnProperty(name = "collections.sms.provider", havingValue = "dev")
@Profile("e2e")
public class DevSmsSender implements SmsSender {

  private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
  private static final Map<String, String> CODES = new ConcurrentHashMap<>();
  private static final Map<String, String> MESSAGES = new ConcurrentHashMap<>();

  @Override
  public void send(String phoneE164, String message) {
    Matcher matcher = CODE.matcher(message);
    MESSAGES.put(phoneE164, message);
    if (matcher.find()) {
      CODES.put(phoneE164, matcher.group(1));
    }
  }

  public static Optional<String> lastCode(String phoneE164) {
    return Optional.ofNullable(CODES.get(phoneE164));
  }

  public static Optional<String> lastMessage(String phoneE164) {
    return Optional.ofNullable(MESSAGES.get(phoneE164));
  }
}
