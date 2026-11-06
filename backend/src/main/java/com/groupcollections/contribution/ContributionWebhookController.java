package com.groupcollections.contribution;

import com.groupcollections.payments.PaystackSignature;
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

  public ContributionWebhookController(PaystackSignature signatures) {
    this.signatures = signatures;
  }

  @PostMapping("/webhooks/momo")
  public ResponseEntity<Void> receive(
      @RequestHeader(value = "x-paystack-signature", required = false) String signature,
      @RequestBody String body) {
    if (!signatures.isValid(body, signature)) {
      log.warn("rejected Paystack callback with an invalid signature");
      throw new InvalidSignatureException();
    }
    return ResponseEntity.noContent().build();
  }
}
