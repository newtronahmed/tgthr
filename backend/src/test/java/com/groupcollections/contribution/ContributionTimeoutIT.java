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
  void timeoutJobResolvesDueContributionsFromThePartnerStatus() {
    String freshSlug = insertGoal("timeout-fresh", "OPEN", null);
    UUID freshId = createId(freshSlug, "0240000001");

    timeoutJob.run();
    assertThat(state(freshId)).isEqualTo("PENDING");

    String paidSlug = insertGoal("timeout-paid", "OPEN", null);
    UUID paidId = createId(paidSlug, "0240000001");
    CLOCK.advance(Duration.ofMinutes(6));
    timeoutJob.run();
    assertThat(state(paidId)).isEqualTo("CONFIRMED");
    assertThat(ledgerCount(paidId)).isEqualTo(4);

    String failedSlug = insertGoal("timeout-failed", "OPEN", null);
    UUID failedId = createId(failedSlug, "0240000005");
    CLOCK.advance(Duration.ofMinutes(6));
    timeoutJob.run();
    assertThat(state(failedId)).isEqualTo("FAILED");
    assertThat(ledgerCount(failedId)).isZero();

    String abandonedSlug = insertGoal("timeout-abandoned", "OPEN", null);
    UUID abandonedId = createId(abandonedSlug, "0240000006");
    CLOCK.advance(Duration.ofMinutes(6));
    timeoutJob.run();
    assertThat(state(abandonedId)).isEqualTo("EXPIRED");
    assertThat(ledgerCount(abandonedId)).isZero();
  }

  private UUID createId(String slug, String momoNumber) {
    Map<String, Object> created = createContribution(slug, 5_000, momoNumber).getBody();
    assertThat(created).isNotNull();
    return UUID.fromString((String) created.get("id"));
  }

  private String state(UUID id) {
    return jdbc.queryForObject("select state from contributions where id = ?", String.class, id);
  }

  private Integer ledgerCount(UUID id) {
    return jdbc.queryForObject(
        "select count(*) from ledger_entries where contribution_id = ?", Integer.class, id);
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
