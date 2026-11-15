package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefundReasonsTest {

  @Test
  void recognisesGhanaMobileNumbersInCommonFormats() {
    assertThat(RefundReasons.containsPhone("payer called from 0240000001")).isTrue();
    assertThat(RefundReasons.containsPhone("024 000 0001")).isTrue();
    assertThat(RefundReasons.containsPhone("024-000-0001")).isTrue();
    assertThat(RefundReasons.containsPhone("+233240000001")).isTrue();
    assertThat(RefundReasons.containsPhone("233 24 000 0001")).isTrue();
  }

  @Test
  void ignoresAmountsDatesIdentifiersAndNonMobileNumbers() {
    assertThat(RefundReasons.containsPhone("duplicate charge")).isFalse();
    assertThat(RefundReasons.containsPhone("refund 2026-11-15")).isFalse();
    assertThat(RefundReasons.containsPhone("amount 10000")).isFalse();
    assertThat(RefundReasons.containsPhone("reference 1234567890123")).isFalse();
    assertThat(RefundReasons.containsPhone("landline 0100000000")).isFalse();
    assertThat(RefundReasons.containsPhone("024000000100")).isFalse();
  }
}
