package com.groupcollections.support;

import com.groupcollections.contribution.AlertPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared harness for HTTP-level tests: real Postgres, the fake partner and a random port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiIntegrationTest.AlertConfiguration.class)
public abstract class ApiIntegrationTest {

  @TestConfiguration
  static class AlertConfiguration {
    @Bean
    @Primary
    AlertPublisher recordingAlertPublisher() {
      return new RecordingAlertPublisher();
    }
  }

  @ServiceConnection
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withStartupTimeout(Duration.ofMinutes(5));

  static {
    POSTGRES.start();
  }

  protected static final FakePaystackServer PAYSTACK = startFakeServer();

  @DynamicPropertySource
  static void partnerProperties(DynamicPropertyRegistry registry) {
    registry.add("collections.payments.paystack.base-url", PAYSTACK::baseUrl);
    registry.add("collections.payments.paystack.secret-key", () -> "test-secret");
    registry.add(
        "collections.security.payer-phone-key",
        () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("collections.scheduling.enabled", () -> "false");
  }

  @Autowired protected TestRestTemplate rest;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected MeterRegistry meterRegistry;

  @BeforeEach
  void resetFakePartner() {
    PAYSTACK.reset();
    RecordingAlertPublisher.reset();
  }

  protected double counter(String name, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  protected String insertGoal(String slugPrefix, String status, Instant deadline) {
    UUID goalId = UUID.randomUUID();
    String slug = slugPrefix + "-" + goalId;
    jdbc.update(
        """
        insert into goals
            (id, slug, title, template, target_type, destination_type, second_approver,
             hold_window_minutes, surplus_rule, status, deadline)
        values (?, ?, 'Nyame biribi', 'FUNERAL', 'OPEN', 'BENEFICIARY_MOMO', 'OFF', 0,
                'PAY_BENEFICIARY', ?, ?)
        """,
        goalId,
        slug,
        status,
        deadline == null ? null : OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC));
    return slug;
  }

  protected ResponseEntity<Map<String, Object>> createContribution(
      String slug, long amountPesewas, String momoNumber) {
    return createContribution(slug, amountPesewas, momoNumber, "idem-" + UUID.randomUUID());
  }

  protected ResponseEntity<Map<String, Object>> createContribution(
      String slug, long amountPesewas, String momoNumber, String idempotencyKey) {
    return rest.exchange(
        "/public/goals/{slug}/contributions",
        HttpMethod.POST,
        new HttpEntity<>(
            contributionRequest(amountPesewas, momoNumber), jsonHeaders(idempotencyKey)),
        new ParameterizedTypeReference<>() {},
        slug);
  }

  protected String webhookUrl() {
    return rest.getRootUri() + "/webhooks/momo";
  }

  protected ResponseEntity<String> createContributionRaw(
      String slug, long amountPesewas, String momoNumber) {
    return rest.exchange(
        "/public/goals/{slug}/contributions",
        HttpMethod.POST,
        new HttpEntity<>(
            contributionRequest(amountPesewas, momoNumber),
            jsonHeaders("idem-" + UUID.randomUUID())),
        String.class,
        slug);
  }

  private static Map<String, Object> contributionRequest(long amountPesewas, String momoNumber) {
    return Map.of("amountPesewas", amountPesewas, "momoNumber", momoNumber, "network", "MTN");
  }

  private static HttpHeaders jsonHeaders(String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return headers;
  }

  private static FakePaystackServer startFakeServer() {
    try {
      return new FakePaystackServer("test-secret");
    } catch (Exception failure) {
      throw new IllegalStateException("could not start the fake Paystack server", failure);
    }
  }
}
