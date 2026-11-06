package com.groupcollections.contribution;

import com.groupcollections.goal.Goal;
import com.groupcollections.goal.GoalNotAcceptingException;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.GoalRepository;
import com.groupcollections.ledger.Money;
import com.groupcollections.payments.PaymentResult;
import com.groupcollections.payments.PaystackClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ContributionService {

  private static final Logger log = LoggerFactory.getLogger(ContributionService.class);

  private final GoalRepository goals;
  private final ContributionRepository contributions;
  private final ContributionConfirmationService confirmation;
  private final FeeCalculator fees;
  private final PaystackClient paystack;
  private final PayerPhoneCipher phones;
  private final Clock clock;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;

  public ContributionService(
      GoalRepository goals,
      ContributionRepository contributions,
      ContributionConfirmationService confirmation,
      FeeCalculator fees,
      PaystackClient paystack,
      PayerPhoneCipher phones,
      Clock clock,
      AlertPublisher alerts,
      MeterRegistry meters) {
    this.goals = goals;
    this.contributions = contributions;
    this.confirmation = confirmation;
    this.fees = fees;
    this.paystack = paystack;
    this.phones = phones;
    this.clock = clock;
    this.alerts = alerts;
    this.meters = meters;
  }

  public ContributionStatus create(
      String slug, String idempotencyKey, CreateContributionRequest request) {
    Optional<ContributionStatus> original = contributions.findByIdempotencyKey(idempotencyKey);
    if (original.isPresent()) {
      return original.get();
    }

    Goal goal = goals.findBySlug(slug).orElseThrow(() -> new GoalNotFoundException(slug));
    if (!goal.acceptsContributions(Instant.now(clock))) {
      throw new GoalNotAcceptingException(slug);
    }
    Money amount = Money.ofPesewas(request.amountPesewas());
    goal.rules().check(amount);

    FeeBreakdown breakdown = fees.calculate(amount);
    UUID id = UUID.randomUUID();
    try {
      contributions.insertInitiated(id, goal.id(), breakdown, request, phones, idempotencyKey);
    } catch (DuplicateKeyException race) {
      return contributions.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
    }

    boolean accepted =
        paystack.initiateCharge(
            id, breakdown.total().pesewas(), request.momoNumber(), request.network());
    meters
        .counter("contributions.prompt", "result", accepted ? "accepted" : "rejected")
        .increment();
    if (accepted) {
      contributions.apply(id, ContributionEvent.PROMPT_SENT);
    } else {
      contributions.apply(id, ContributionEvent.PROMPT_REJECTED);
    }
    return contributions.status(id);
  }

  public ContributionStatus status(UUID id) {
    return contributions.findStatus(id).orElseThrow(() -> new ContributionNotFoundException(id));
  }

  public void handle(PaymentResult result) {
    log.info(
        "event=callback_received reference={} success={}",
        result.providerReference(),
        result.success());
    Optional<ContributionPayment> found =
        contributions.findByProviderReference(result.providerReference());
    if (found.isEmpty()) {
      meters.counter("contributions.callback", "result", "unknown_reference").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.UNKNOWN_REFERENCE,
              result.providerReference(),
              "callback arrived for an unknown provider reference"));
      return;
    }
    ContributionPayment payment = found.get();
    if (payment.total().pesewas() != result.amountPesewas()) {
      meters.counter("contributions.callback", "result", "amount_mismatch").increment();
      alerts.publish(
          new Alert(
              Alert.AlertType.AMOUNT_MISMATCH,
              result.providerReference(),
              "expected " + payment.total().pesewas() + " pesewas, got " + result.amountPesewas()));
      return;
    }
    if (result.success()) {
      meters.counter("contributions.callback", "result", "success").increment();
      confirmation.confirm(payment);
      return;
    }
    meters.counter("contributions.callback", "result", "failed").increment();
    contributions.apply(payment.id(), ContributionEvent.PAYMENT_FAILED);
  }
}
