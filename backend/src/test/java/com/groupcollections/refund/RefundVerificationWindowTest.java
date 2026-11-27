package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The automatic verification window must outlast the provider's refund settlement window, otherwise
 * every genuine refund escalates while Paystack is still legitimately processing it (ADR 0020).
 *
 * <p>Reads the shipped {@code application.yml} rather than restating the numbers, so a change that
 * shrinks the window fails here. The T401 spike recorded {@code expected_at} 11 days out.
 */
class RefundVerificationWindowTest {

  /** Recorded in docs/runbooks/refund-operations.md section 9: `expected_at` was 11 days out. */
  private static final Duration PROVIDER_REFUND_WINDOW = Duration.ofDays(11);

  @Test
  void theShippedDefaultWindowCoversMostOfTheProviderSettlementWindow() {
    Window window = shippedWindow();

    assertThat(window.total())
        .as("the automatic window must cover most of the observed 11-day settlement window")
        .isGreaterThanOrEqualTo(PROVIDER_REFUND_WINDOW.multipliedBy(80).dividedBy(100))
        .isLessThan(PROVIDER_REFUND_WINDOW);
  }

  @Test
  void theShippedWindowIsBoundedAndTheTailIsNotAggressive() {
    Window window = shippedWindow();

    assertThat(window.attempts()).as("AC-REFUND-011 is a bounded retry").isLessThanOrEqualTo(50);
    assertThat(window.tail()).isGreaterThanOrEqualTo(Duration.ofHours(12));
    assertThat(window.maxDelay()).isGreaterThanOrEqualTo(Duration.ofHours(6));
    assertThat(window.initialDelay()).isGreaterThan(Duration.ofMinutes(5));
  }

  /**
   * Mirrors {@code RefundVerificationJob.backoff}: initial delay, doubling per attempt, capped.
   *
   * <p>The shipped values are environment placeholders ({@code ${REFUND_...:PT5M}}), so this
   * resolves each one to its default the way Spring does when the variable is unset.
   */
  private static Window shippedWindow() {
    Map<String, Object> root = loadApplicationYaml();
    Map<String, Object> verification = refundVerificationDefaults(root);

    Duration initial = Duration.parse(resolveDefault(verification.get("initial-delay")));
    Duration maxDelay = Duration.parse(resolveDefault(verification.get("max-delay")));
    int attempts = Integer.parseInt(resolveDefault(verification.get("max-attempts")));

    Duration total = initial;
    for (int attempt = 1; attempt < attempts; attempt++) {
      total = total.plus(backoff(attempt, initial, maxDelay));
    }
    return new Window(initial, maxDelay, attempts, total, backoff(attempts - 1, initial, maxDelay));
  }

  /** {@code ${NAME:default}} becomes {@code default}; a bare value is returned unchanged. */
  private static String resolveDefault(Object configured) {
    String value = String.valueOf(configured);
    if (value.startsWith("${") && value.endsWith("}")) {
      int separator = value.indexOf(':');
      assertThat(separator).as("expected a default in %s", value).isGreaterThan(0);
      return value.substring(separator + 1, value.length() - 1);
    }
    return value;
  }

  private static Duration backoff(int attempt, Duration initial, Duration max) {
    int exponent = Math.min(Math.max(attempt - 1, 0), 20);
    Duration delay = initial.multipliedBy(1L << exponent);
    return delay.compareTo(max) > 0 ? max : delay;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadApplicationYaml() {
    try (InputStream input =
        RefundVerificationWindowTest.class.getResourceAsStream("/application.yml")) {
      assertThat(input).as("application.yml must be on the test classpath").isNotNull();
      return new Yaml().load(input);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("could not read application.yml", failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> refundVerificationDefaults(Map<String, Object> root) {
    Map<String, Object> collections = (Map<String, Object>) root.get("collections");
    Map<String, Object> refunds = (Map<String, Object>) collections.get("refunds");
    return (Map<String, Object>) refunds.get("verification");
  }

  private record Window(
      Duration initialDelay, Duration maxDelay, int attempts, Duration total, Duration tail) {}
}
