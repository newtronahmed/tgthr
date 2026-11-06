package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class ContributionTimeoutIT extends ApiIntegrationTest {

  static final MutableClock CLOCK = new MutableClock(Instant.now());

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired ContributionTimeoutJob timeoutJob;

  @BeforeEach
  void resetClock() {
    CLOCK.set(Instant.now());
  }

  @Test
  @Criterion("AC-CONTRIB-008")
  void timeoutJobConfirmsAPaidContributionAndExpiresAnUnpaidOne() {
    String paidSlug = insertGoal("timeout-paid", "OPEN", null);
    Map<String, Object> paid = createContribution(paidSlug, 5_000, "0240000001").getBody();
    UUID paidId = UUID.fromString((String) paid.get("id"));

    CLOCK.advance(Duration.ofMinutes(6));
    timeoutJob.run();

    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, paidId))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where contribution_id = ?",
                Integer.class,
                paidId))
        .isEqualTo(4);

    String unpaidSlug = insertGoal("timeout-unpaid", "OPEN", null);
    Map<String, Object> unpaid = createContribution(unpaidSlug, 5_000, "0240000005").getBody();
    UUID unpaidId = UUID.fromString((String) unpaid.get("id"));
    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, unpaidId))
        .isEqualTo("PENDING");

    CLOCK.advance(Duration.ofMinutes(6));
    timeoutJob.run();

    assertThat(
            jdbc.queryForObject(
                "select state from contributions where id = ?", String.class, unpaidId))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where contribution_id = ?",
                Integer.class,
                unpaidId))
        .isZero();
  }

  static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      this.now = instant;
    }

    void advance(Duration duration) {
      this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
