package com.groupcollections.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically flags EXECUTING payouts that outlive the verification threshold (T344). */
@Component
@ConditionalOnProperty(name = "collections.payout.execution.enabled", havingValue = "true")
public class PayoutStuckJob {

  private final PayoutVerificationService verification;

  public PayoutStuckJob(PayoutVerificationService verification) {
    this.verification = verification;
  }

  @Scheduled(fixedDelayString = "${collections.payout.stuck-scan-ms:60000}")
  public void run() {
    verification.verifyStuck();
  }
}
