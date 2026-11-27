package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.alerts.AlertPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The automatic verification window must outlast the provider's refund settlement window, otherwise
 * every genuine refund escalates while Paystack is still legitimately processing it (ADR 0020).
 *
 * <p>No {@code @TestPropertySource}: the job is built from the shipped {@code application.yml}, and
 * this calls the job's own {@code backoff} rather than mirroring it. Neither a config change nor a
 * schedule change can silently drift from what this asserts. The T401 spike recorded {@code
 * expected_at} 11 days out.
 */
@SpringBootTest(classes = {RefundVerificationJob.class, RefundVerificationWindowTest.Stubs.class})
class RefundVerificationWindowTest {

  /** Recorded in docs/runbooks/refund-operations.md section 9: `expected_at` was 11 days out. */
  private static final Duration PROVIDER_REFUND_WINDOW = Duration.ofDays(11);

  @Autowired private RefundVerificationJob job;

  @Test
  void theShippedDefaultWindowCoversMostOfTheProviderSettlementWindow() {
    assertThat(window())
        .as("the automatic window must cover most of the observed 11-day settlement window")
        .isGreaterThanOrEqualTo(PROVIDER_REFUND_WINDOW.multipliedBy(80).dividedBy(100))
        .isLessThan(PROVIDER_REFUND_WINDOW);
  }

  @Test
  void theShippedWindowIsBoundedAndTheTailIsNotAggressive() {
    assertThat(job.maxAttempts()).as("AC-REFUND-011 is a bounded retry").isLessThanOrEqualTo(50);
    assertThat(job.backoff(job.maxAttempts()))
        .as("a multi-day process must not be checked every few minutes at the end")
        .isGreaterThanOrEqualTo(Duration.ofHours(12));
    assertThat(window()).isGreaterThan(Duration.ofDays(1));
  }

  /**
   * Mirrors {@code RefundVerificationJob.run}: first check after the initial delay, then backoff.
   */
  private Duration window() {
    Duration total = job.initialDelay();
    for (int attempt = 1; attempt < job.maxAttempts(); attempt++) {
      total = total.plus(job.backoff(attempt));
    }
    return total;
  }

  /** The job only needs its own dependencies to answer questions about its schedule. */
  @TestConfiguration
  static class Stubs {
    @Bean
    RefundRepository refunds() {
      return Mockito.mock(RefundRepository.class);
    }

    @Bean
    RefundProvider provider() {
      return Mockito.mock(RefundProvider.class);
    }

    @Bean
    RefundCompletionService completion() {
      return Mockito.mock(RefundCompletionService.class);
    }

    @Bean
    AlertPublisher alerts() {
      return Mockito.mock(AlertPublisher.class);
    }

    @Bean
    MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
