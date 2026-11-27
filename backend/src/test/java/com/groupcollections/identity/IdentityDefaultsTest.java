package com.groupcollections.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * T169: the identity defaults are money/privacy-affecting configuration, so they are pinned here
 * rather than left to survive by accident.
 *
 * <p>Reads the shipped {@code application.yml} and resolves each {@code ${NAME:default}}
 * placeholder the way Spring does when the variable is unset, so this asserts what actually
 * deploys.
 */
class IdentityDefaultsTest {

  @Test
  void theShippedIdentityDefaultsAreTheDocumentedOnes() {
    Map<String, Object> identity = identityDefaults();

    // Five minutes is long enough for an SMS to arrive and short enough that a leaked code is
    // near-useless. The runbook and the spec both say five minutes.
    assertThat(Duration.parse(resolve(identity.get("otp-ttl")))).isEqualTo(Duration.ofMinutes(5));

    assertThat(Integer.parseInt(resolve(identity.get("otp-max-attempts")))).isEqualTo(5);

    // Resend spam protection: one code per number per minute.
    assertThat(Integer.parseInt(resolve(identity.get("otp-resend-seconds")))).isEqualTo(60);

    // Organizer sessions are long-lived because a goal lives for weeks, not a browser session.
    assertThat(Duration.parse(resolve(identity.get("session-ttl")))).isEqualTo(Duration.ofDays(30));
  }

  @Test
  void theOtpTtlIsShortEnoughToBeUsefulAndLongEnoughToArrive() {
    Duration ttl = Duration.parse(resolve(identityDefaults().get("otp-ttl")));

    assertThat(ttl).isBetween(Duration.ofMinutes(1), Duration.ofMinutes(15));
  }

  @Test
  void theSessionTtlIsBoundedAndNotUnlimited() {
    Duration ttl = Duration.parse(resolve(identityDefaults().get("session-ttl")));

    assertThat(ttl).isLessThanOrEqualTo(Duration.ofDays(30));
    assertThat(ttl).isGreaterThan(Duration.ofDays(1));
  }

  /** {@code ${NAME:default}} becomes {@code default}; a bare value is returned unchanged. */
  private static String resolve(Object configured) {
    String value = String.valueOf(configured);
    if (value.startsWith("${") && value.endsWith("}")) {
      int separator = value.indexOf(':');
      assertThat(separator).as("expected a default in %s", value).isGreaterThan(0);
      return value.substring(separator + 1, value.length() - 1);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> identityDefaults() {
    try (InputStream input = IdentityDefaultsTest.class.getResourceAsStream("/application.yml")) {
      assertThat(input).as("application.yml must be on the test classpath").isNotNull();
      Map<String, Object> root = new Yaml().load(input);
      Map<String, Object> collections = (Map<String, Object>) root.get("collections");
      return (Map<String, Object>) collections.get("identity");
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("could not read application.yml", failure);
    }
  }
}
