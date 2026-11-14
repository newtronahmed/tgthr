package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

  @Test
  void generatesTenUniqueCodesWithMatchingHashes() {
    RecoveryCodes.Generated generated = RecoveryCodes.generate(new SecureRandom());

    assertThat(generated.plaintext()).hasSize(10).doesNotHaveDuplicates();
    assertThat(generated.hashes()).hasSize(10).doesNotHaveDuplicates();
    for (int index = 0; index < generated.plaintext().size(); index++) {
      assertThat(
              RecoveryCodes.matches(
                  generated.plaintext().get(index), generated.hashes().get(index)))
          .isTrue();
    }
  }

  @Test
  void rejectsAWrongRecoveryCode() {
    RecoveryCodes.Generated generated = RecoveryCodes.generate(new SecureRandom());

    assertThat(RecoveryCodes.matches("not-a-code", generated.hashes().getFirst())).isFalse();
    assertThat(RecoveryCodes.matches(generated.plaintext().getFirst(), "not-a-hash")).isFalse();
  }

  @Test
  void acceptsLowercaseAndSurroundingWhitespace() {
    RecoveryCodes.Generated generated = RecoveryCodes.generate(new SecureRandom());
    String code = generated.plaintext().getFirst();

    assertThat(
            RecoveryCodes.matches("  " + code.toLowerCase() + "\n", generated.hashes().getFirst()))
        .isTrue();
  }

  @Test
  void neverPrintsPlaintextCodes() {
    RecoveryCodes.Generated generated = RecoveryCodes.generate(new SecureRandom());

    assertThat(generated.toString())
        .contains("[REDACTED]")
        .doesNotContain(generated.plaintext().getFirst())
        .doesNotContain(generated.plaintext().getLast());
  }
}
