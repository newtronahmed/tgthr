package com.groupcollections.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "collections.payout.execution.enabled", havingValue = "true")
public class PayoutWorkerJob {

  private final PayoutExecutionService execution;

  public PayoutWorkerJob(PayoutExecutionService execution) {
    this.execution = execution;
  }

  @Scheduled(fixedDelayString = "${collections.payout.worker-scan-ms:60000}")
  public void run() {
    for (int i = 0; i < 20 && execution.executeNext() > 0; i++) {
      // Drain a bounded batch; each claim is independent.
    }
  }
}
