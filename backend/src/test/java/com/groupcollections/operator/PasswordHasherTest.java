package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher(10_000, new SecureRandom());

  @Test
  void hashesAndMatchesThePassword() {
    String encoded = hasher.hash("correct horse battery staple");

    assertThat(encoded).startsWith("pbkdf2-sha256$10000$");
    assertThat(hasher.matches("correct horse battery staple", encoded)).isTrue();
    assertThat(hasher.matches("wrong password", encoded)).isFalse();
  }

  @Test
  void usesAUniqueSaltForEveryHash() {
    String first = hasher.hash("same password");
    String second = hasher.hash("same password");

    assertThat(first).isNotEqualTo(second);
    assertThat(hasher.matches("same password", first)).isTrue();
    assertThat(hasher.matches("same password", second)).isTrue();
  }

  @Test
  void rejectsMalformedStoredHashesAndNullPasswords() {
    assertThat(hasher.matches("password", null)).isFalse();
    assertThat(hasher.matches("password", "")).isFalse();
    assertThat(hasher.matches("password", "not-a-hash")).isFalse();
    assertThat(hasher.matches("password", "pbkdf2-sha256$nope$c2FsdA$aGFzaA")).isFalse();
    assertThat(hasher.matches("password", "pbkdf2-sha256$10000$not-base64$aGFzaA")).isFalse();
    assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultWorkFactorMeetsTheAdr() {
    assertThat(PasswordHasher.DEFAULT_ITERATIONS).isGreaterThanOrEqualTo(600_000);
  }
}
