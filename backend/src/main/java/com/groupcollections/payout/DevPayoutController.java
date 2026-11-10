package com.groupcollections.payout;

import com.groupcollections.identity.DevSmsSender;
import com.groupcollections.identity.PhoneNormalizer;
import com.groupcollections.security.PhoneCipher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** E2E-only payout setup and approval lookup; never enabled outside the e2e profile. */
@RestController
@RequestMapping("/dev")
@Profile("e2e")
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class DevPayoutController {

  private static final Pattern TOKEN = Pattern.compile("/approve/([A-Za-z0-9_-]+)");
  private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

  private final JdbcTemplate jdbc;
  private final PhoneCipher phones;

  public DevPayoutController(JdbcTemplate jdbc, PhoneCipher phones) {
    this.jdbc = jdbc;
    this.phones = phones;
  }

  @PostMapping("/goals/{goalId}/settle")
  @Transactional
  public Map<String, Object> settle(
      @PathVariable UUID goalId, @RequestParam(defaultValue = "5000") long amountPesewas) {
    String currency =
        jdbc.queryForObject("select currency_code from goals where id = ?", String.class, goalId);
    UUID contributionId = UUID.randomUUID();
    String reference = "e2e-" + contributionId;
    String payer = "+233240000777";
    Instant now = Instant.now();
    jdbc.update(
        """
        insert into contributions
            (id, goal_id, amount_minor_units, processing_fee_minor_units, platform_fee_minor_units,
             total_minor_units, currency_code, state, provider_reference, payer_phone_ciphertext,
             payer_phone_hash, idempotency_key, confirmed_at)
        values (?, ?, ?, 0, 0, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?)
        """,
        contributionId,
        goalId,
        amountPesewas,
        amountPesewas,
        currency,
        reference,
        phones.encrypt(payer),
        phones.hash(payer),
        "e2e-" + contributionId,
        Timestamp.from(now));
    UUID posting = UUID.randomUUID();
    jdbc.update(
        """
        insert into ledger_entries
            (posting_set_id, contribution_id, account, entry_type, direction, amount_minor_units,
             currency_code)
        values (?, ?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', ?, ?),
               (?, ?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', ?, ?)
        """,
        posting,
        contributionId,
        amountPesewas,
        currency,
        posting,
        contributionId,
        amountPesewas,
        currency);
    UUID settlementId = UUID.randomUUID();
    jdbc.update(
        """
        insert into settlements
            (id, provider, provider_settlement_reference, settlement_date,
             gross_amount_minor_units, currency_code, fees_minor_units, net_amount_minor_units,
             direction, status)
        values (?, 'PAYSTACK', ?, current_date, ?, ?, 0, ?, 'CREDIT', 'PENDING')
        """,
        settlementId,
        "e2e-" + settlementId,
        amountPesewas,
        currency,
        amountPesewas);
    jdbc.update(
        """
        insert into settlement_items
            (id, settlement_id, contribution_id, item_type, direction, gross_minor_units,
             currency_code, fee_minor_units, net_minor_units, goal_amount_minor_units,
             provider_item_reference)
        values (?, ?, ?, 'CONTRIBUTION', 'CREDIT', ?, ?, 0, ?, ?, ?)
        """,
        UUID.randomUUID(),
        settlementId,
        contributionId,
        amountPesewas,
        currency,
        amountPesewas,
        amountPesewas,
        "e2e-" + UUID.randomUUID());
    jdbc.update(
        "update settlements set status = 'SETTLED', received_at = now() where id = ?",
        settlementId);
    return Map.of("amountPesewas", amountPesewas);
  }

  @GetMapping("/payout-approvals/last")
  public ResponseEntity<Map<String, String>> lastApproval(@RequestParam String phone) {
    String normalized = PhoneNormalizer.normalize(phone);
    String message = DevSmsSender.lastMessage(normalized).orElse(null);
    if (message == null) {
      return ResponseEntity.notFound().build();
    }
    Matcher token = TOKEN.matcher(message);
    Matcher code = CODE.matcher(message);
    if (!token.find() || !code.find()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(Map.of("token", token.group(1), "code", code.group(1)));
  }
}
