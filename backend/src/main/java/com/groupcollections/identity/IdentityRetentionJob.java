package com.groupcollections.identity;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes old OTP challenges and sessions so identity tables do not grow without bound. */
@Component
public class IdentityRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(IdentityRetentionJob.class);
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration retention;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;

  public IdentityRetentionJob(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${collections.identity.retention:P1D}") Duration retention,
      MeterRegistry meters,
      AlertPublisher alerts) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.retention = retention;
    this.meters = meters;
    this.alerts = alerts;
  }

  @Scheduled(fixedDelay = 3_600_000)
  public void purge() {
    Timestamp cutoff = Timestamp.from(Instant.now(clock).minus(retention));
    try {
      int challenges =
          jdbc.update(
              "delete from otp_challenges where coalesce(consumed_at, expires_at) < ?", cutoff);
      int sessions =
          jdbc.update(
              "delete from user_sessions where coalesce(revoked_at, expires_at) < ?", cutoff);
      meters.counter("identity.retention.challenges").increment(challenges);
      meters.counter("identity.retention.sessions").increment(sessions);
      if (challenges + sessions > 0) {
        log.info("identity retention removed {} challenges and {} sessions", challenges, sessions);
      }
    } catch (RuntimeException failure) {
      meters.counter("identity.retention.failed").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.RETENTION_FAILURE,
              "identity-retention",
              failure.getClass().getSimpleName()));
      log.error("identity retention failed", failure);
      throw failure;
    }
  }
}
