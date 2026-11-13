package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PayoutVerificationServiceTest {

  @Test
  void rejectsThresholdAtOrBelowProviderTimeout() {
    assertThatThrownBy(
            () ->
                PayoutVerificationService.validateThreshold(
                    Duration.ofSeconds(1), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stuck-after");
    assertThatThrownBy(
            () ->
                PayoutVerificationService.validateThreshold(
                    Duration.ofSeconds(5), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonPositiveThreshold() {
    assertThatThrownBy(
            () -> PayoutVerificationService.validateThreshold(Duration.ZERO, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                PayoutVerificationService.validateThreshold(
                    Duration.ofSeconds(-1), Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsThresholdAboveProviderTimeout() {
    assertThatCode(
            () ->
                PayoutVerificationService.validateThreshold(
                    Duration.ofMinutes(10), Duration.ofSeconds(5)))
        .doesNotThrowAnyException();
  }
}
