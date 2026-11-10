package com.groupcollections.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases holds whose positive window has expired. */
@Component
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PayoutHoldJob {

  private final PayoutHoldService holds;

  public PayoutHoldJob(PayoutHoldService holds) {
    this.holds = holds;
  }

  @Scheduled(fixedDelayString = "${collections.payout.hold-scan-ms:60000}")
  public void releaseExpired() {
    holds.releaseExpired();
  }
}
