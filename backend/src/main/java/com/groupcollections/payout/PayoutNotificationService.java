package com.groupcollections.payout;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.identity.PhoneNormalizer;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.security.PhoneCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Notifies funded contributors when a payout completes. Called by the execution worker after the
 * payout transaction commits; failures alert and never change the payout.
 */
@Service
public class PayoutNotificationService {

  private static final Logger log = LoggerFactory.getLogger(PayoutNotificationService.class);

  private final PayoutRepository payouts;
  private final JdbcTemplate jdbc;
  private final PhoneCipher phones;
  private final SmsSender sms;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;
  private final Clock clock;

  public PayoutNotificationService(
      PayoutRepository payouts,
      JdbcTemplate jdbc,
      PhoneCipher phones,
      SmsSender sms,
      AlertPublisher alerts,
      MeterRegistry meters,
      Clock clock) {
    this.payouts = payouts;
    this.jdbc = jdbc;
    this.phones = phones;
    this.sms = sms;
    this.alerts = alerts;
    this.meters = meters;
    this.clock = clock;
  }

  public int notifyCompleted(UUID payoutId) {
    Payout payout = payouts.findById(payoutId).orElse(null);
    if (payout == null || !payout.state().equals("COMPLETED")) {
      return 0;
    }
    List<Funded> funded =
        jdbc.query(
            """
            select id, payer_phone_ciphertext, payer_phone_hash
            from contributions
            where goal_id = ? and state = 'CONFIRMED' and requires_reconciliation = false
            order by confirmed_at, id
            """,
            (resultSet, rowNumber) ->
                new Funded(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getBytes("payer_phone_ciphertext"),
                    resultSet.getBytes("payer_phone_hash")),
            payout.goalId());
    Set<String> seen = new HashSet<>();
    Instant now = Instant.now(clock);
    int sent = 0;
    for (Funded contributor : funded) {
      String key = HexFormat.of().formatHex(contributor.payerPhoneHash());
      if (!seen.add(key)) {
        continue;
      }
      UUID notificationId =
          jdbc
              .query(
                  """
                  insert into payout_notifications
                      (payout_id, contribution_id, payer_phone_hash, sent_at)
                  values (?, ?, ?, ?)
                  on conflict (payout_id, payer_phone_hash) do nothing
                  returning id
                  """,
                  (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                  payout.id(),
                  contributor.contributionId(),
                  contributor.payerPhoneHash(),
                  Timestamp.from(now))
              .stream()
              .findFirst()
              .orElse(null);
      if (notificationId == null) {
        continue;
      }
      try {
        sms.send(
            PhoneNormalizer.normalize(phones.decrypt(contributor.payerPhoneCiphertext())),
            "A GHS "
                + amount(payout.amountPesewas())
                + " payout for your goal was sent. No action is needed.");
        meters.counter("payout.notification.sent").increment();
        sent++;
      } catch (RuntimeException failure) {
        jdbc.update("delete from payout_notifications where id = ?", notificationId);
        meters.counter("payout.notification.failed").increment();
        alerts.publish(
            new Alert(
                Alert.AlertType.SMS_FAILURE,
                payout.id().toString(),
                "payout contributor notification failed"));
        log.warn("payout contributor notification failed");
      }
    }
    return sent;
  }

  private static String amount(long pesewas) {
    return (pesewas / 100) + "." + String.format("%02d", pesewas % 100);
  }

  private record Funded(UUID contributionId, byte[] payerPhoneCiphertext, byte[] payerPhoneHash) {}
}
