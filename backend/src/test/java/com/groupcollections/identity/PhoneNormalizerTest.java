package com.groupcollections.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNormalizerTest {

  @Test
  void localNumbersBecomeE164() {
    assertThat(PhoneNormalizer.normalize("0240000001")).isEqualTo("+233240000001");
    assertThat(PhoneNormalizer.normalize("024 000 0001")).isEqualTo("+233240000001");
    assertThat(PhoneNormalizer.normalize("024-000-0001")).isEqualTo("+233240000001");
  }

  @Test
  void e164NumbersPassThrough() {
    assertThat(PhoneNormalizer.normalize("+233240000001")).isEqualTo("+233240000001");
  }

  @Test
  void invalidNumbersAreRejected() {
    assertThatThrownBy(() -> PhoneNormalizer.normalize("12345"))
        .isInstanceOf(IdentityException.class);
    assertThatThrownBy(() -> PhoneNormalizer.normalize("+2342400000001"))
        .isInstanceOf(IdentityException.class);
    assertThatThrownBy(() -> PhoneNormalizer.normalize(null)).isInstanceOf(IdentityException.class);
  }
}
