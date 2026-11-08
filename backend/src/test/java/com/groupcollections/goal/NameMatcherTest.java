package com.groupcollections.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameMatcherTest {

  @Test
  void matchesIgnoringCaseAndSpacing() {
    assertThat(NameMatcher.matches("  Ama   Mensah ", "ama mensah")).isTrue();
    assertThat(NameMatcher.matches("AMA MENSAH", "Ama  Mensah")).isTrue();
  }

  @Test
  void rejectsDifferentOrMissingNames() {
    assertThat(NameMatcher.matches("Kofi Mensah", "Ama Mensah")).isFalse();
    assertThat(NameMatcher.matches("   ", "Ama Mensah")).isFalse();
    assertThat(NameMatcher.matches("Ama Mensah", null)).isFalse();
  }
}
