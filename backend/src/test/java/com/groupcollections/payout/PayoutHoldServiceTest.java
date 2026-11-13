package com.groupcollections.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutHoldServiceTest {

  @Test
  void rejectsAMinimumHoldBelowOneAtStartup() {
    assertThatThrownBy(() -> new PayoutHoldService(null, Clock.systemUTC(), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minimum-hold-minutes");
  }

  @Test
  void rejectsAStoredHoldBelowTheConfiguredMinimum() {
    PayoutHoldService holds = new PayoutHoldService(null, Clock.systemUTC(), 3);

    assertThatThrownBy(() -> holds.startHold(UUID.randomUUID(), 2, Instant.now()))
        .isInstanceOfSatisfying(
            PayoutException.class,
            failure -> assertThat(failure.code()).isEqualTo("HOLD_REQUIRED"));
  }
}
