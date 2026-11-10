package com.groupcollections.payout;

import com.groupcollections.alerts.Alert;
import com.groupcollections.alerts.AlertPublisher;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.OrganizerGoal;
import com.groupcollections.goal.OrganizerGoalRepository;
import com.groupcollections.identity.IdentityException;
import com.groupcollections.identity.SmsSender;
import com.groupcollections.identity.UserRepository;
import com.groupcollections.security.PhoneCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Hashed SMS approval challenges for a designated group member. */
@Service
public class PayoutApprovalService {

  private static final Logger log = LoggerFactory.getLogger(PayoutApprovalService.class);
  private static final Duration TOKEN_TTL = Duration.ofHours(24);
  private static final Duration CODE_TTL = Duration.ofMinutes(10);
  private static final int MAX_ATTEMPTS = 5;

  private final PayoutApprovalRepository approvals;
  private final PayoutRepository payouts;
  private final OrganizerGoalRepository goals;
  private final PayoutHoldService holds;
  private final PhoneCipher phones;
  private final SmsSender sms;
  private final UserRepository users;
  private final SecureRandom random;
  private final Clock clock;
  private final MeterRegistry meters;
  private final AlertPublisher alerts;
  private final String webOrigin;
  private final long resendSeconds;

  public PayoutApprovalService(
      PayoutApprovalRepository approvals,
      PayoutRepository payouts,
      OrganizerGoalRepository goals,
      PayoutHoldService holds,
      PhoneCipher phones,
      SmsSender sms,
      UserRepository users,
      SecureRandom random,
      Clock clock,
      MeterRegistry meters,
      AlertPublisher alerts,
      @Value("${collections.web.origin:http://localhost:3000}") String webOrigin,
      @Value("${collections.payout.approval-resend-seconds:60}") long resendSeconds) {
    this.approvals = approvals;
    this.payouts = payouts;
    this.goals = goals;
    this.holds = holds;
    this.phones = phones;
    this.sms = sms;
    this.users = users;
    this.random = random;
    this.clock = clock;
    this.meters = meters;
    this.alerts = alerts;
    this.webOrigin = webOrigin;
    this.resendSeconds = resendSeconds;
  }

  /**
   * Sends the approval SMS after the payout transaction has committed, so no goal row lock is held
   * during the external call. A failed send consumes the just-created challenge; the payout stays
   * REQUESTED and a retry with the same idempotency key can send again.
   */
  public void sendForPayout(UUID payoutId) {
    Payout payout =
        payouts.findById(payoutId).orElseThrow(() -> new IllegalStateException("payout missing"));
    if (!payout.state().equals("REQUESTED")) {
      return;
    }
    Instant now = Instant.now(clock);
    if (approvals.findActiveForPayout(payoutId, now).isPresent()) {
      return;
    }
    approvals.consumeExpiredForPayout(payoutId, now);
    Challenge challenge = createChallenge(payoutId, now);
    try {
      sendSms(payout, goal(payout), challenge);
    } catch (RuntimeException failure) {
      approvals.consume(challenge.id(), now);
      throw failure;
    }
  }

  private Challenge createChallenge(UUID payoutId, Instant now) {
    String token = token();
    String code = code();
    UUID id =
        approvals.insert(
            payoutId,
            tokenHash(token),
            phones.hashOtpCode(code),
            now.plus(TOKEN_TTL),
            now.plus(CODE_TTL),
            MAX_ATTEMPTS,
            now);
    return new Challenge(id, token, code);
  }

  private void sendSms(Payout payout, OrganizerGoal goal, Challenge challenge) {
    byte[] approverCiphertext =
        goals
            .findApproverPhoneCiphertext(goal.id(), payout.requestedBy())
            .orElseThrow(PayoutException::approverRequired);
    try {
      sms.send(
          phones.decrypt(approverCiphertext),
          "Approve GHS "
              + amount(payout.amountPesewas())
              + " payout for "
              + goal.title()
              + ": "
              + webOrigin
              + "/approve/"
              + challenge.token()
              + " code "
              + challenge.code());
    } catch (RuntimeException failure) {
      meters.counter("payout.approval.sms.failed").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.SMS_FAILURE, payout.id().toString(), "payout approval SMS failed"));
      log.warn("payout approval SMS failed");
      if (failure instanceof IdentityException identity) {
        throw identity;
      }
      throw IdentityException.smsUnavailable();
    }
    meters.counter("payout.approval.sent").increment();
  }

  public byte[] rateLimitKey(String token) {
    return approvals
        .findByTokenHash(tokenHash(token))
        .map(this::payout)
        .flatMap(payout -> goals.findApproverPhoneHash(payout.goalId(), payout.requestedBy()))
        .orElse(null);
  }

  @Transactional(readOnly = true)
  public PayoutApprovalView view(String token) {
    PayoutApproval approval = active(token);
    Payout payout = payout(approval);
    OrganizerGoal goal = goal(payout);
    return view(approval, payout, goal);
  }

  @Transactional(noRollbackFor = PayoutException.class)
  public PayoutView verify(String token, String code) {
    PayoutApproval approval = active(token);
    Instant now = Instant.now(clock);
    if (now.isAfter(approval.codeExpiresAt())) {
      throw PayoutException.approvalInvalid();
    }
    int attempts =
        approvals
            .incrementAttempts(approval.id(), now)
            .orElseThrow(PayoutException::approvalInvalid);
    if (!MessageDigest.isEqual(approval.codeHash(), phones.hashOtpCode(code))) {
      if (attempts >= approval.maxAttempts()) {
        approvals.consume(approval.id(), now);
        PayoutStateMachine.transition(
            PayoutStateMachine.PayoutState.REQUESTED,
            PayoutStateMachine.PayoutEvent.APPROVAL_EXHAUSTED,
            false);
        Payout exhausted = payout(approval);
        cancelRequested(exhausted, now, "APPROVAL_EXHAUSTED");
        notifyOrganizerAfterCommit(exhausted, "approval attempts exhausted");
      }
      throw PayoutException.approvalInvalid();
    }
    if (approvals.consume(approval.id(), now) != 1) {
      throw PayoutException.approvalInvalid();
    }
    Payout payout = payout(approval);
    if (!payout.state().equals("REQUESTED")) {
      throw PayoutException.approvalInvalid();
    }
    OrganizerGoal goal = goal(payout);
    PayoutStateMachine.transition(
        PayoutStateMachine.PayoutState.REQUESTED, PayoutStateMachine.PayoutEvent.APPROVE, false);
    if (payouts.markApproved(payout.id(), now) != 1) {
      throw PayoutException.approvalInvalid();
    }
    payouts.appendEvent(payout.id(), "APPROVED", "REQUESTED", "APPROVED", now);
    holds.startHold(payout.id(), goal.holdWindowMinutes(), now);
    meters.counter("payout.approval.approved").increment();
    return new PayoutView(
        payout.id(),
        payout.goalId(),
        payout.amountPesewas(),
        payout.feePesewas(),
        payout.netPesewas(),
        payout.currency(),
        "HOLD",
        payout.createdAt());
  }

  public PayoutApprovalView resend(String token) {
    PayoutApproval approval = active(token);
    Instant now = Instant.now(clock);
    if (approval.createdAt().plusSeconds(resendSeconds).isAfter(now)) {
      throw PayoutException.resendTooSoon();
    }
    if (approvals.consume(approval.id(), now) != 1) {
      throw PayoutException.approvalInvalid();
    }
    sendForPayout(approval.payoutId());
    meters.counter("payout.approval.resent").increment();
    return null;
  }

  @Transactional
  public PayoutView decline(String token) {
    PayoutApproval approval = active(token);
    Instant now = Instant.now(clock);
    if (approvals.consume(approval.id(), now) != 1) {
      throw PayoutException.approvalInvalid();
    }
    Payout payout = payout(approval);
    if (!payout.state().equals("REQUESTED")) {
      throw PayoutException.approvalInvalid();
    }
    PayoutStateMachine.transition(
        PayoutStateMachine.PayoutState.REQUESTED, PayoutStateMachine.PayoutEvent.DECLINE, false);
    cancelRequested(payout, now, "DECLINED");
    notifyOrganizerAfterCommit(payout, "approver declined");
    meters.counter("payout.approval.declined").increment();
    return new PayoutView(
        payout.id(),
        payout.goalId(),
        payout.amountPesewas(),
        payout.feePesewas(),
        payout.netPesewas(),
        payout.currency(),
        "CANCELLED",
        payout.createdAt());
  }

  @Transactional
  public int expire() {
    Instant now = Instant.now(clock);
    List<PayoutApproval> expired = approvals.findExpiredTokens(now);
    int cancelled = 0;
    for (PayoutApproval approval : expired) {
      if (approvals.consume(approval.id(), now) != 1) {
        continue;
      }
      Payout payout = payout(approval);
      if (payout.state().equals("REQUESTED")) {
        PayoutStateMachine.transition(
            PayoutStateMachine.PayoutState.REQUESTED,
            PayoutStateMachine.PayoutEvent.APPROVAL_EXPIRED,
            false);
        if (payouts.markCancelled(payout.id(), "REQUESTED", now) == 1) {
          payouts.appendEvent(payout.id(), "APPROVAL_EXPIRED", "REQUESTED", "CANCELLED", now);
          notifyOrganizerAfterCommit(payout, "approval expired");
          cancelled++;
        }
      }
    }
    meters.counter("payout.approval.expired").increment(cancelled);
    return cancelled;
  }

  private PayoutApproval active(String token) {
    PayoutApproval approval =
        approvals.findByTokenHash(tokenHash(token)).orElseThrow(PayoutException::approvalInvalid);
    if (approval.consumedAt() != null || Instant.now(clock).isAfter(approval.tokenExpiresAt())) {
      throw PayoutException.approvalInvalid();
    }
    return approval;
  }

  private Payout payout(PayoutApproval approval) {
    return payouts.findById(approval.payoutId()).orElseThrow(PayoutException::approvalInvalid);
  }

  private OrganizerGoal goal(Payout payout) {
    return goals
        .findByIdAndCreator(payout.goalId(), payout.requestedBy())
        .orElseThrow(() -> new GoalNotFoundException(payout.goalId().toString()));
  }

  private void cancelRequested(Payout payout, Instant now, String event) {
    if (payout.state().equals("REQUESTED")
        && payouts.markCancelled(payout.id(), "REQUESTED", now) == 1) {
      payouts.appendEvent(payout.id(), event, "REQUESTED", "CANCELLED", now);
    }
  }

  private void notifyOrganizerAfterCommit(Payout payout, String reason) {
    Runnable notification = () -> notifyOrganizer(payout, reason);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              notification.run();
            }
          });
    } else {
      notification.run();
    }
  }

  private void notifyOrganizer(Payout payout, String reason) {
    try {
      byte[] ciphertext =
          users
              .findPhoneCiphertext(payout.requestedBy())
              .orElseThrow(() -> new IllegalStateException("organizer phone missing"));
      sms.send(
          phones.decrypt(ciphertext),
          "Your GHS "
              + amount(payout.amountPesewas())
              + " payout was cancelled: "
              + reason
              + ". No funds left the goal.");
      meters.counter("payout.organizer_notification.sent").increment();
    } catch (RuntimeException failure) {
      meters.counter("payout.organizer_notification.failed").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.SMS_FAILURE,
              payout.id().toString(),
              "organizer payout notification failed"));
      log.warn("organizer payout notification failed");
    }
  }

  private PayoutApprovalView view(PayoutApproval approval, Payout payout, OrganizerGoal goal) {
    return new PayoutApprovalView(
        payout.id(),
        goal.title(),
        goal.secondApproverName(),
        payout.amountPesewas(),
        payout.feePesewas(),
        payout.netPesewas(),
        payout.currency(),
        payout.state(),
        approval.tokenExpiresAt());
  }

  private String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String code() {
    return String.format("%06d", random.nextInt(1_000_000));
  }

  private static byte[] tokenHash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private record Challenge(UUID id, String token, String code) {}

  private static String amount(long pesewas) {
    return (pesewas / 100) + "." + String.format("%02d", pesewas % 100);
  }
}
