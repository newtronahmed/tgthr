package com.groupcollections.contribution;

import com.groupcollections.payments.PaystackSignature;
import com.groupcollections.payments.PaystackWebhookMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContributionWebhookController {

  private static final Logger log = LoggerFactory.getLogger(ContributionWebhookController.class);

  private final PaystackSignature signatures;
  private final PaystackWebhookMapper mapper;
  private final ContributionService contributions;
  private final MeterRegistry meters;

  public ContributionWebhookController(
      PaystackSignature signatures,
      PaystackWebhookMapper mapper,
      ContributionService contributions,
      MeterRegistry meters) {
    this.signatures = signatures;
    this.mapper = mapper;
    this.contributions = contributions;
    this.meters = meters;
  }

  @PostMapping("/webhooks/momo")
  public ResponseEntity<Void> receive(
      @RequestHeader(value = "x-paystack-signature", required = false) String signature,
      @RequestBody String body) {
    if (!signatures.isValid(body, signature)) {
      meters.counter("contributions.callback", "result", "invalid_signature").increment();
      log.warn("rejected Paystack callback with an invalid signature");
      throw new InvalidSignatureException();
    }
    contributions.handle(mapper.map(body));
    return ResponseEntity.noContent().build();
  }
}
