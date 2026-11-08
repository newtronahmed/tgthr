package com.groupcollections.organizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeSmsSender;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "collections.rate-limit.enabled=true",
      "collections.rate-limit.otp-per-phone-per-minute=2",
      "collections.rate-limit.otp-per-ip-per-minute=100",
      "collections.rate-limit.otp-verify-per-phone-per-minute=2",
      "collections.rate-limit.otp-verify-per-ip-per-minute=100",
      "collections.identity.otp-resend-seconds=0",
      "collections.identity.otp-ttl=PT5M",
      "collections.identity.otp-max-attempts=5",
      "collections.identity.session-ttl=P30D"
    })
@Import(OrganizerRateLimitIT.SmsConfiguration.class)
class OrganizerRateLimitIT {

  @TestConfiguration
  static class SmsConfiguration {
    @Bean
    @Primary
    SmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }
  }

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
          .withStartupTimeout(Duration.ofMinutes(5));

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("collections.payments.paystack.base-url", () -> "http://localhost:1");
    registry.add("collections.payments.paystack.secret-key", () -> "test-secret");
    registry.add(
        "collections.security.payer-phone-key",
        () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("collections.scheduling.enabled", () -> "false");
    registry.add("collections.web.origin", () -> "http://localhost:3000");
  }

  @Autowired private TestRestTemplate rest;

  @BeforeEach
  void resetSms() {
    FakeSmsSender.reset();
  }

  @Test
  @Criterion("AC-ORG-004")
  void verificationRateLimitsReturn429() {
    request("0240000023");
    String code = FakeSmsSender.latestCode();
    assertThat(verify("0240000023", "000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(verify("0240000023", "000000").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<Map<String, Object>> limited = verify("0240000023", code);
    assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(limited.getBody()).isNotNull();
    assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");

    request("0240000024");
    ResponseEntity<Map<String, Object>> other = verify("0240000024", FakeSmsSender.latestCode());
    assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @Criterion("AC-ORG-004")
  void otpRateLimitsReturn429() {
    assertThat(request("0240000021").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(request("0240000021").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    ResponseEntity<Map<String, Object>> limited = request("0240000021");
    assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(limited.getBody()).isNotNull();
    assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");

    assertThat(request("0240000022").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
  }

  private ResponseEntity<Map<String, Object>> verify(String phone, String code) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/auth/otp/verify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("phone", phone, "code", code, "displayName", "Ama"), headers),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> request(String phone) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/auth/otp/request",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("phone", phone), headers),
        new ParameterizedTypeReference<>() {});
  }
}
