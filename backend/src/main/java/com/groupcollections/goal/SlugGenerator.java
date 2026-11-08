package com.groupcollections.goal;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class SlugGenerator {

  private final SecureRandom random;

  public SlugGenerator(SecureRandom random) {
    this.random = random;
  }

  public String next() {
    byte[] bytes = new byte[12];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
