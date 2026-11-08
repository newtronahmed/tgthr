package com.groupcollections.organizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeSmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
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

@Import(OrganizerIdentityInvariantsTest.SmsConfiguration.class)
class OrganizerIdentityInvariantsTest extends ApiIntegrationTest {

  @TestConfiguration
  static class SmsConfiguration {
    @Bean
    @Primary
    SmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }
  }

  @BeforeEach
  void resetSms() {
    FakeSmsSender.reset();
  }

  @Test
  @Criterion("INV-IDENT-001")
  void protectedRequestsRequireALiveUnrevokedSession() throws Exception {
    ResponseEntity<Map<String, Object>> missing =
        rest.exchange(
            "/organizer/me",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            new ParameterizedTypeReference<>() {});
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(missing.getBody()).isNotNull();
    assertThat(missing.getBody().get("code")).isEqualTo("UNAUTHENTICATED");

    String expired = newSession("0240000041");
    jdbc.update(
        "update user_sessions set expires_at = created_at + interval '1 microsecond' where token_hash = decode(?, 'hex')",
        sha256Hex(expired));
    assertThat(me(expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    String revoked = newSession("0240000042");
    assertThat(logout(revoked).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(me(revoked).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(randomToken()).isNotBlank();
    assertThat(me(randomToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Criterion("INV-IDENT-002")
  void challengesAreSingleUseExpiringAndAttemptLimited() {
    requestOtp("0240000043");
    String code = FakeSmsSender.latestCode();
    assertThat(verify("0240000043", code).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(verify("0240000043", code).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    requestOtp("0240000044");
    String expiredCode = FakeSmsSender.latestCode();
    UUID expiredId = latestChallengeId();
    jdbc.update(
        "update otp_challenges set expires_at = now() - interval '1 second' where id = ?",
        expiredId);
    assertThat(verify("0240000044", expiredCode).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    requestOtp("0240000045");
    String correct = FakeSmsSender.latestCode();
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(verify("0240000045", wrongCode(correct)).getStatusCode())
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    assertThat(verify("0240000045", correct).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Criterion("INV-IDENT-003")
  void phoneNumbersAreEncryptedAndNeverReturned() {
    String phone = "0240000046";
    String token = newSession(phone);

    Map<String, Object> stored =
        jdbc.queryForMap(
            "select phone_ciphertext, phone_hash from users where phone_hash = (select phone_hash from otp_challenges order by created_at desc limit 1)");
    assertThat((byte[]) stored.get("phone_ciphertext"))
        .isNotEqualTo(phone.getBytes(StandardCharsets.UTF_8));
    assertThat((byte[]) stored.get("phone_hash")).hasSize(32);

    ResponseEntity<String> me =
        rest.exchange(
            "/organizer/me", HttpMethod.GET, new HttpEntity<>(cookie(token)), String.class);
    assertThat(me.getBody()).doesNotContain(phone).doesNotContain("phone");
    ResponseEntity<String> goals =
        rest.exchange(
            "/organizer/goals", HttpMethod.GET, new HttpEntity<>(cookie(token)), String.class);
    assertThat(goals.getBody()).doesNotContain(phone).doesNotContain("destinationPhone");
  }

  private String newSession(String phone) {
    requestOtp(phone);
    ResponseEntity<Map<String, Object>> verified = verify(phone, FakeSmsSender.latestCode());
    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    return token(verified);
  }

  private void requestOtp(String phone) {
    assertThat(
            rest.exchange(
                    "/auth/otp/request",
                    HttpMethod.POST,
                    json(Map.of("phone", phone)),
                    new ParameterizedTypeReference<Map<String, Object>>() {})
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
  }

  private ResponseEntity<Map<String, Object>> verify(String phone, String code) {
    Map<String, Object> body = new HashMap<>();
    body.put("phone", phone);
    body.put("code", code);
    body.put("displayName", "Ama");
    body.put("legalName", "Ama Mensah");
    return rest.exchange(
        "/auth/otp/verify", HttpMethod.POST, json(body), new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> me(String token) {
    return rest.exchange(
        "/organizer/me",
        HttpMethod.GET,
        new HttpEntity<>(cookie(token)),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<String> logout(String token) {
    return rest.exchange(
        "/auth/logout", HttpMethod.POST, new HttpEntity<>(cookie(token)), String.class);
  }

  private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  private static HttpHeaders cookie(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + token);
    return headers;
  }

  private static String token(ResponseEntity<Map<String, Object>> response) {
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).startsWith("tgthr_session=");
    return setCookie.substring("tgthr_session=".length(), setCookie.indexOf(';'));
  }

  private UUID latestChallengeId() {
    return jdbc.queryForObject(
        "select id from otp_challenges order by created_at desc limit 1", UUID.class);
  }

  private static String wrongCode(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  private static String randomToken() {
    return HexFormat.of().formatHex(new byte[32]);
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
