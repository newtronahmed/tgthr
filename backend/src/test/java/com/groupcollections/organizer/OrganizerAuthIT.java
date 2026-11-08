package com.groupcollections.organizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.identity.SmsSender;
import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import com.groupcollections.support.FakeSmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

@Import(OrganizerAuthIT.SmsConfiguration.class)
class OrganizerAuthIT extends ApiIntegrationTest {

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
  @Criterion("AC-ORG-001")
  void otpRequestSendsOneCodeAndRevealsNothing() {
    ResponseEntity<Map<String, Object>> response = requestOtp("0240000001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNull();
    assertThat(FakeSmsSender.sent()).hasSize(1);
    assertThat(FakeSmsSender.sent().getFirst().phone()).isEqualTo("+233240000001");
    assertThat(FakeSmsSender.latestCode()).matches("\\d{6}");
    byte[] phoneHash =
        jdbc.queryForObject(
            "select phone_hash from otp_challenges order by created_at desc limit 1", byte[].class);
    assertThat(count("select count(*) from users where phone_hash = ?", phoneHash)).isZero();
    assertThat(count("select count(*) from otp_challenges where phone_hash = ?", phoneHash))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-ORG-002")
  void validOtpCreatesARotatedSession() throws Exception {
    requestOtp("0240000002");
    byte[] phoneHash = latestPhoneHash();

    ResponseEntity<Map<String, Object>> verified =
        verifyOtp("0240000002", FakeSmsSender.latestCode(), "Ama", "Ama Mensah");

    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(verified.getBody()).isNotNull();
    assertThat(verified.getBody()).containsKeys("id", "displayName", "createdAt");
    assertThat(verified.getBody()).doesNotContainKeys("phone", "phoneNumber");
    String token = sessionToken(verified);
    assertThat(token).isNotBlank();
    String setCookie = verified.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie)
        .contains("HttpOnly", "SameSite=Lax", "Secure", "Path=/", "Max-Age=2592000");

    ResponseEntity<Map<String, Object>> me = me(token);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(me.getBody()).isNotNull();
    assertThat(me.getBody().get("id")).isEqualTo(verified.getBody().get("id"));

    String tokenHash = sha256Hex(token);
    assertThat(
            count(
                "select count(*) from user_sessions where token_hash = decode(?, 'hex')",
                tokenHash))
        .isEqualTo(1);
    assertThat(count("select count(*) from user_sessions where token_hash = ?::bytea", token))
        .isZero();

    Thread.sleep(1_100);
    requestOtp("0240000002");
    ResponseEntity<Map<String, Object>> second =
        verifyOtp("0240000002", FakeSmsSender.latestCode(), null, null);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sessionToken(second)).isNotEqualTo(token);
    assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(count("select count(*) from users where phone_hash = ?", phoneHash)).isEqualTo(1);
  }

  @Test
  @Criterion("AC-ORG-002")
  void returningOrganizerCanCorrectNames() throws Exception {
    requestOtp("0240000030");
    ResponseEntity<Map<String, Object>> first =
        verifyOtp("0240000030", FakeSmsSender.latestCode(), "Ama", "Ama Mensah");
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    Thread.sleep(1_100);
    requestOtp("0240000030");
    ResponseEntity<Map<String, Object>> second =
        verifyOtp("0240000030", FakeSmsSender.latestCode(), "Ama", "Ama M. Mensah");
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(me(sessionToken(second)).getBody()).isNotNull();
    assertThat(me(sessionToken(second)).getBody().get("legalName")).isEqualTo("Ama M. Mensah");
  }

  @Test
  @Criterion("AC-ORG-003")
  void invalidExpiredReusedAndOverAttemptedCodesAreRejected() {
    requestOtp("0240000011");
    String wrong = wrongCode(FakeSmsSender.latestCode());
    byte[] wrongPhone = latestPhoneHash();
    ResponseEntity<Map<String, Object>> invalid = verifyOtp("0240000011", wrong, null, null);
    assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(invalid.getBody()).isNotNull();
    assertThat(invalid.getBody().get("code")).isEqualTo("INVALID_OTP");
    assertThat(count("select count(*) from users where phone_hash = ?", wrongPhone)).isZero();

    requestOtp("0240000012");
    String expiredCode = FakeSmsSender.latestCode();
    UUID expiredId = latestChallengeId();
    jdbc.update(
        "update otp_challenges set expires_at = now() - interval '1 second' where id = ?",
        expiredId);
    byte[] expiredPhone = latestPhoneHash();
    ResponseEntity<Map<String, Object>> expired = verifyOtp("0240000012", expiredCode, null, null);
    assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(count("select count(*) from users where phone_hash = ?", expiredPhone)).isZero();

    requestOtp("0240000013");
    String correct = FakeSmsSender.latestCode();
    byte[] burnedPhone = latestPhoneHash();
    for (int attempt = 0; attempt < 5; attempt++) {
      ResponseEntity<Map<String, Object>> burned =
          verifyOtp("0240000013", wrongCode(correct), null, null);
      assertThat(burned.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    ResponseEntity<Map<String, Object>> burnedOut = verifyOtp("0240000013", correct, null, null);
    assertThat(burnedOut.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(count("select count(*) from users where phone_hash = ?", burnedPhone)).isZero();

    jdbc.update("update otp_challenges set created_at = now() where id = ?", latestChallengeId());
    ResponseEntity<Map<String, Object>> burnedResend = requestOtp("0240000013");
    assertThat(burnedResend.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(burnedResend.getBody()).isNotNull();
    assertThat(burnedResend.getBody().get("code")).isEqualTo("RESEND_TOO_SOON");

    requestOtp("0240000014");
    String reusedCode = FakeSmsSender.latestCode();
    ResponseEntity<Map<String, Object>> first = verifyOtp("0240000014", reusedCode, null, null);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<Map<String, Object>> reused = verifyOtp("0240000014", reusedCode, null, null);
    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Criterion("AC-ORG-010")
  void missingExpiredAndRevokedSessionsReturn401() throws Exception {
    ResponseEntity<Map<String, Object>> missing =
        rest.exchange(
            "/organizer/me",
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            new ParameterizedTypeReference<>() {});
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(missing.getBody()).isNotNull();
    assertThat(missing.getBody().get("code")).isEqualTo("UNAUTHENTICATED");

    String expired = newSession("0240000015");
    jdbc.update(
        "update user_sessions set expires_at = created_at + interval '1 microsecond' where token_hash = decode(?, 'hex')",
        sha256Hex(expired));
    assertThat(me(expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    String revoked = newSession("0240000016");
    assertThat(logout(revoked, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(me(revoked).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @Criterion("AC-ORG-011")
  void crossSiteStateChangesAreRejected() {
    String token = newSession("0240000017");

    ResponseEntity<String> foreign =
        logoutWithHeaders(token, Map.of(HttpHeaders.ORIGIN, "http://evil.example"));
    assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(foreign.getBody()).contains("CROSS_SITE_REQUEST");

    ResponseEntity<String> crossSite =
        logoutWithHeaders(token, Map.of("Sec-Fetch-Site", "cross-site"));
    assertThat(crossSite.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.OK);

    requestOtp("0240000025");
    String code = FakeSmsSender.latestCode();
    ResponseEntity<String> crossSiteVerify =
        verifyWithOrigin("0240000025", code, "http://evil.example");
    assertThat(crossSiteVerify.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(crossSiteVerify.getBody()).contains("CROSS_SITE_REQUEST");
    assertThat(verifyOtp("0240000025", code, "Ama", null).getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> allowed =
        logoutWithHeaders(token, Map.of(HttpHeaders.ORIGIN, "http://localhost:3000"));
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  @Criterion("AC-ORG-014")
  void logoutRevokesTheSession() throws Exception {
    String token = newSession("0240000018");

    ResponseEntity<String> out = logout(token, null);

    assertThat(out.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(out.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            count(
                "select count(*) from user_sessions where token_hash = decode(?, 'hex') and revoked_at is not null",
                sha256Hex(token)))
        .isEqualTo(1);
  }

  @Test
  @Criterion("AC-ORG-015")
  void resendAfterTheWindowInvalidatesThePreviousCode() throws Exception {
    requestOtp("0240000019");
    String firstCode = FakeSmsSender.latestCode();

    ResponseEntity<Map<String, Object>> tooSoon = requestOtp("0240000019");
    assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(tooSoon.getBody()).isNotNull();
    assertThat(tooSoon.getBody().get("code")).isEqualTo("RESEND_TOO_SOON");

    assertThat(verifyOtp("0240000019", firstCode, "Ama", null).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    jdbc.update("update otp_challenges set created_at = now() where id = ?", latestChallengeId());
    ResponseEntity<Map<String, Object>> afterVerify = requestOtp("0240000019");
    assertThat(afterVerify.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(afterVerify.getBody()).isNotNull();
    assertThat(afterVerify.getBody().get("code")).isEqualTo("RESEND_TOO_SOON");

    Thread.sleep(1_100);
    ResponseEntity<Map<String, Object>> resent = requestOtp("0240000019");
    assertThat(resent.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(FakeSmsSender.latestCode()).isNotEqualTo(firstCode);

    assertThat(verifyOtp("0240000019", firstCode, null, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(verifyOtp("0240000019", FakeSmsSender.latestCode(), null, null).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> verifyWithOrigin(String phone, String code, String origin) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.ORIGIN, origin);
    return rest.exchange(
        "/auth/otp/verify",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("phone", phone, "code", code, "displayName", "Ama"), headers),
        String.class);
  }

  private String newSession(String phone) {
    requestOtp(phone);
    ResponseEntity<Map<String, Object>> verified =
        verifyOtp(phone, FakeSmsSender.latestCode(), null, null);
    assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
    return sessionToken(verified);
  }

  private ResponseEntity<String> logout(String token, String origin) {
    Map<String, String> extra = origin == null ? Map.of() : Map.of(HttpHeaders.ORIGIN, origin);
    return logoutWithHeaders(token, extra);
  }

  private ResponseEntity<String> logoutWithHeaders(String token, Map<String, String> extraHeaders) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + token);
    extraHeaders.forEach(headers::add);
    return rest.exchange("/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), String.class);
  }

  private UUID latestChallengeId() {
    return jdbc.queryForObject(
        "select id from otp_challenges order by created_at desc limit 1", UUID.class);
  }

  private byte[] latestPhoneHash() {
    return jdbc.queryForObject(
        "select phone_hash from otp_challenges order by created_at desc limit 1", byte[].class);
  }

  private static String wrongCode(String code) {
    return code.equals("000000") ? "000001" : "000000";
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

  private ResponseEntity<Map<String, Object>> requestOtp(String phone) {
    return rest.exchange(
        "/auth/otp/request",
        HttpMethod.POST,
        json(Map.of("phone", phone)),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> verifyOtp(
      String phone, String code, String displayName, String legalName) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("phone", phone);
    body.put("code", code);
    if (displayName != null) {
      body.put("displayName", displayName);
    }
    if (legalName != null) {
      body.put("legalName", legalName);
    }
    return rest.exchange(
        "/auth/otp/verify", HttpMethod.POST, json(body), new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> me(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, "tgthr_session=" + token);
    return rest.exchange(
        "/organizer/me",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        new ParameterizedTypeReference<>() {});
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private static String sessionToken(ResponseEntity<Map<String, Object>> response) {
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).startsWith("tgthr_session=");
    return setCookie.substring("tgthr_session=".length(), setCookie.indexOf(';'));
  }

  private static HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
