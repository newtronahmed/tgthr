package com.groupcollections.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cancels payouts whose approval token expired. */
@Component
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PayoutApprovalExpiryJob {

  private final PayoutApprovalService approvals;

  public PayoutApprovalExpiryJob(PayoutApprovalService approvals) {
    this.approvals = approvals;
  }

  @Scheduled(fixedDelayString = "${collections.payout.approval-scan-ms:60000}")
  public void expire() {
    approvals.expire();
  }
}
