package com.groupcollections.contribution;

import com.groupcollections.payments.PaymentResult;
import com.groupcollections.payments.PaystackClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ContributionService {

  private static final Logger log = LoggerFactory.getLogger(ContributionService.class);

  private final ContributionRepository contributions;
  private final ContributionRegistration registration;
  private final ContributionConfirmationService confirmation;
  private final PaystackClient paystack;
  private final AlertPublisher alerts;
  private final MeterRegistry meters;

  public ContributionService(
      ContributionRepository contributions,
      ContributionRegistration registration,
      ContributionConfirmationService confirmation,
      PaystackClient paystack,
      AlertPublisher alerts,
      MeterRegistry meters) {
    this.contributions = contributions;
    this.registration = registration;
    this.confirmation = confirmation;
    this.paystack = paystack;
    this.alerts = alerts;
    this.meters = meters;
  }

  public ContributionStatus create(
      String slug, String idempotencyKey, CreateContributionRequest request) {
    ContributionRegistration.Registration created =
        registration.register(slug, idempotencyKey, request);
    if (!created.created()) {
      return created.status();
    }

    boolean accepted =
        paystack.initiateCharge(
            created.id(), created.totalPesewas(), request.momoNumber(), request.network());
    meters
        .counter("contributions.prompt", "result", accepted ? "accepted" : "rejected")
        .increment();
    if (accepted) {
      contributions.apply(created.id(), ContributionEvent.PROMPT_SENT);
    } else {
      contributions.apply(created.id(), ContributionEvent.PROMPT_REJECTED);
    }
    return contributions.status(created.id());
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
