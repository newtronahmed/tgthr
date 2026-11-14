package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OperatorSecretCipherTest {

  private static final String KEY =
      Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

  @Test
  void roundTripsTheTotpSecret() {
    OperatorSecretCipher cipher = new OperatorSecretCipher(KEY, new SecureRandom());

    byte[] sealed = cipher.encrypt("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");

    assertThat(sealed).isNotEmpty();
    assertThat(cipher.decrypt(sealed)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
  }

  @Test
  void rejectsAKeyOfTheWrongLengthOrAnEmptyKey() {
    assertThatThrownBy(() -> new OperatorSecretCipher("", new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OperatorSecretCipher("c2hvcnQ=", new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OperatorSecretCipher("not-base64!", new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnInvalidCiphertext() {
    OperatorSecretCipher cipher = new OperatorSecretCipher(KEY, new SecureRandom());

    assertThatThrownBy(() -> cipher.decrypt(new byte[4])).isInstanceOf(IllegalStateException.class);
  }
}
