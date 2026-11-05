package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakePaystackServer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContributionApiIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

  static final FakePaystackServer PAYSTACK = startFakeServer();

  @DynamicPropertySource
  static void partnerProperties(DynamicPropertyRegistry registry) {
    registry.add("collections.payments.paystack.base-url", PAYSTACK::baseUrl);
    registry.add("collections.payments.paystack.secret-key", () -> "test-secret");
    registry.add(
        "collections.security.payer-phone-key",
        () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  }

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;

  @Test
  @Criterion("AC-CONTRIB-001")
  void openGoalCreatesContributionAndRequestsPrompt() {
    UUID goalId = UUID.randomUUID();
    String slug = "goal-" + goalId;
    jdbc.update(
        """
        insert into goals
            (id, slug, title, template, target_type, destination_type, second_approver,
             hold_window_minutes, surplus_rule)
        values (?, ?, 'Akosua and Kwame', 'WEDDING', 'OPEN', 'BENEFICIARY_MOMO', 'OFF', 0,
                'PAY_BENEFICIARY')
        """,
        goalId,
        slug);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "idem-" + goalId);
    Map<String, Object> request =
        Map.of(
            "amountPesewas", 5_000,
            "momoNumber", "0240000001",
            "network", "MTN",
            "displayName", "Kofi");

    ResponseEntity<Map<String, Object>> response =
        rest.exchange(
            "/public/goals/{slug}/contributions",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            new ParameterizedTypeReference<>() {},
            slug);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    Map<String, Object> status = response.getBody();
    assertThat(status).isNotNull();
    assertThat(status.get("state")).isEqualTo("PENDING");
    assertThat(status.get("amountPesewas")).isEqualTo(5_000);
    assertThat(status)
        .containsKeys(
            "id", "processingFeePesewas", "platformFeePesewas", "feePesewas", "totalPesewas");

    assertThat(PAYSTACK.promptCount()).isEqualTo(1);

    Map<String, Object> stored =
        jdbc.queryForMap("select id, state from contributions where goal_id = ?", goalId);
    assertThat(stored.get("state")).isEqualTo("PENDING");
    assertThat(stored.get("id")).isEqualTo(UUID.fromString((String) status.get("id")));

    Integer transitions =
        jdbc.queryForObject(
            """
            select count(*) from contribution_events
            where contribution_id = ? and from_state = 'INITIATED' and to_state = 'PENDING'
            """,
            Integer.class,
            stored.get("id"));
    assertThat(transitions).isEqualTo(1);
  }

  private static FakePaystackServer startFakeServer() {
    try {
      return new FakePaystackServer();
    } catch (Exception failure) {
      throw new IllegalStateException("could not start the fake Paystack server", failure);
    }
  }
}
