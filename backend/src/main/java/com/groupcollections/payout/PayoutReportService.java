package com.groupcollections.payout;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.goal.Goal;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.GoalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stores a contributor report and alerts the operator; it never changes the payout. */
@Service
public class PayoutReportService {

  private static final Logger log = LoggerFactory.getLogger(PayoutReportService.class);
  private static final Set<String> PUBLIC_STATES =
      Set.of("HOLD", "READY", "EXECUTING", "COMPLETED");

  private final GoalRepository goals;
  private final PayoutRepository payouts;
  private final JdbcTemplate jdbc;
  private final PayoutReportRateLimiter rateLimiter;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;
  private final Clock clock;

  public PayoutReportService(
      GoalRepository goals,
      PayoutRepository payouts,
      JdbcTemplate jdbc,
      PayoutReportRateLimiter rateLimiter,
      AlertPublisher alerts,
      MeterRegistry meters,
      Clock clock) {
    this.goals = goals;
    this.payouts = payouts;
    this.jdbc = jdbc;
    this.rateLimiter = rateLimiter;
    this.alerts = alerts;
    this.meters = meters;
    this.clock = clock;
  }

  public void report(String slug, UUID payoutId, PayoutReportRequest request, String clientIp) {
    rateLimiter.check(clientIp);
    Goal goal = goals.findBySlug(slug).orElseThrow(() -> new GoalNotFoundException(slug));
    Payout payout =
        payouts
            .findById(payoutId)
            .filter(candidate -> candidate.goalId().equals(goal.id()))
            .filter(candidate -> PUBLIC_STATES.contains(candidate.state()))
            .orElseThrow(
                () -> {
                  meters.counter("payout.report.rejected", "reason", "not_visible").increment();
                  return PayoutException.notFound();
                });
    if (request.contributionId() != null
        && jdbc.queryForObject(
                "select count(*) from contributions where id = ? and goal_id = ?",
                Integer.class,
                request.contributionId(),
                goal.id())
            == 0) {
      meters.counter("payout.report.rejected", "reason", "contribution_mismatch").increment();
      throw PayoutException.invalid("contribution does not belong to this goal");
    }
    Instant now = Instant.now(clock);
    jdbc.update(
        """
        insert into payout_reports (payout_id, contribution_id, reason, created_at)
        values (?, cast(? as uuid), ?, ?)
        """,
        payout.id(),
        request.contributionId(),
        request.reason().trim(),
        Timestamp.from(now));
    alerts.publish(
        new Alert(
            Alert.AlertType.PAYOUT_REPORTED,
            payout.id().toString(),
            "contributor reported a payout"));
    meters.counter("payout.report.received").increment();
    log.info("payout report received {}", payout.id());
  }
}
