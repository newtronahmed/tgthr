package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OperatorPasswordTest {

  private final PasswordHasher hasher = new PasswordHasher();

  @Test
  void hashesAndMatchesThePassword() {
    String encoded = hasher.hash("correct horse battery staple");

    assertThat(encoded).startsWith("pbkdf2-sha256$600000$");
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
  void rejectsMalformedStoredHashes() {
    String password = "a-long-password";
    assertThat(hasher.matches(password, null)).isFalse();
    assertThat(hasher.matches(password, "")).isFalse();
    assertThat(hasher.matches(password, "not-a-hash")).isFalse();
    assertThat(hasher.matches(password, "pbkdf2-sha256$nope$c2FsdA$aGFzaA")).isFalse();
    assertThat(hasher.matches(password, "pbkdf2-sha256$600000$not-base64$aGFzaA")).isFalse();
  }

  @Test
  void rejectsWeakIterationsAndWrongSaltLengths() {
    String password = "a-long-password";
    String validHash = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    String sixteenByteSalt = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    String eightByteSalt = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[8]);

    assertThat(hasher.matches(password, "pbkdf2-sha256$10000$" + sixteenByteSalt + "$" + validHash))
        .isFalse();
    assertThat(hasher.matches(password, "pbkdf2-sha256$600000$" + eightByteSalt + "$" + validHash))
        .isFalse();
    assertThatThrownBy(() -> new PasswordHasher(10_000, new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enforcesPasswordLength() {
    assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hasher.hash("short")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hasher.hash("x".repeat(PasswordHasher.MAX_PASSWORD_LENGTH + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(hasher.hash("x".repeat(PasswordHasher.MIN_PASSWORD_LENGTH))).isNotBlank();
  }

  @Test
  void defaultWorkFactorMeetsTheAdr() {
    assertThat(PasswordHasher.DEFAULT_ITERATIONS).isGreaterThanOrEqualTo(600_000);
  }
}
