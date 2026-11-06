package com.groupcollections.contribution;

import com.groupcollections.goal.Goal;
import com.groupcollections.goal.GoalNotAcceptingException;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.GoalRepository;
import com.groupcollections.ledger.Money;
import com.groupcollections.payments.PaymentResult;
import com.groupcollections.payments.PaystackClient;
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

  public ContributionService(
      GoalRepository goals,
      ContributionRepository contributions,
      ContributionConfirmationService confirmation,
      FeeCalculator fees,
      PaystackClient paystack,
      PayerPhoneCipher phones,
      Clock clock) {
    this.goals = goals;
    this.contributions = contributions;
    this.confirmation = confirmation;
    this.fees = fees;
    this.paystack = paystack;
    this.phones = phones;
    this.clock = clock;
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
    if (accepted) {
      contributions.apply(id, ContributionEvent.PROMPT_SENT);
    } else {
      contributions.apply(id, ContributionEvent.PROMPT_REJECTED);
    }
    return contributions.status(id);
  }

  public void handle(PaymentResult result) {
    Optional<ContributionPayment> found =
        contributions.findByProviderReference(result.providerReference());
    if (found.isEmpty()) {
      log.warn("callback for unknown provider reference {}", result.providerReference());
      return;
    }
    ContributionPayment payment = found.get();
    if (payment.total().pesewas() != result.amountPesewas()) {
      log.warn(
          "callback amount mismatch for {}: expected {}, got {}",
          result.providerReference(),
          payment.total().pesewas(),
          result.amountPesewas());
      return;
    }
    if (result.success()) {
      confirmation.confirm(payment);
      return;
    }
    contributions.apply(payment.id(), ContributionEvent.PAYMENT_FAILED);
  }
}
