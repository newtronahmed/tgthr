package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "collections.operator.enabled=true",
      "collections.operator.totp-secret-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "collections.operator.session-ttl=PT1H",
      "collections.operator.idle-timeout=PT30M",
      "collections.operator.mfa-ttl=PT5M",
      "collections.operator.max-failed-attempts=5",
      "collections.operator.lockout=PT15M"
    })
class OperatorAuthIT extends ApiIntegrationTest {

  private static final String PASSWORD = "correct horse battery staple";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;

  @Test
  void passwordSuccessCreatesOnlyAnMfaChallenge() {
    TestOperator operator =
        createOperator("refund.one", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);

    ResponseEntity<Map<String, Object>> login = login(operator.username(), PASSWORD);

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).containsKeys("challengeToken", "expiresAt");
    assertThat(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    assertThat(login.getBody()).doesNotContainKeys("id", "roles", "password", "phone");

    String challenge = (String) login.getBody().get("challengeToken");
    byte[] storedHash =
        jdbc.queryForObject(
            "select token_hash from operator_mfa_challenges where operator_id = ?",
            byte[].class,
            operator.id());
    assertThat(storedHash).isEqualTo(sha256(challenge));
    assertThat(new String(storedHash, StandardCharsets.UTF_8)).isNotEqualTo(challenge);
    assertThat(count("select count(*) from operator_sessions where operator_id = ?", operator.id()))
        .isZero();
    assertThat(auditCount(operator.id(), "LOGIN_PASSWORD_OK")).isEqualTo(1);
    assertThat(counter("operator.auth.login", "result", "challenge")).isGreaterThanOrEqualTo(1);
  }

  @Test
  void wrongPasswordIsGenericAndLocksAfterFiveAttempts() {
    TestOperator operator =
        createOperator("refund.two", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);

    for (int attempt = 0; attempt < 5; attempt++) {
      ResponseEntity<Map<String, Object>> response =
          login(operator.username(), "wrong password value");
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    }

    assertThat(
            jdbc.queryForObject(
                "select failed_attempts from operators where id = ?", Integer.class, operator.id()))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select locked_until is not null from operators where id = ?",
                Boolean.class,
                operator.id()))
        .isTrue();
    assertThat(
            count(
                "select count(*) from operator_mfa_challenges where operator_id = ?",
                operator.id()))
        .isZero();

    ResponseEntity<Map<String, Object>> locked = login(operator.username(), PASSWORD);
    assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(locked.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    assertThat(auditCount(operator.id(), "LOGIN_FAILED")).isEqualTo(5);
    assertThat(auditCount(operator.id(), "LOGIN_BLOCKED")).isGreaterThanOrEqualTo(1);
  }

  @Test
  void correctTotpIssuesAnOperatorSessionAndMeUsesRoles() {
    TestOperator operator =
        createOperator(
            "refund.three",
            OperatorStatus.ACTIVE,
            OperatorRole.REFUND_OPERATOR,
            OperatorRole.RECONCILER);

    ResponseEntity<Map<String, Object>> login = login(operator.username(), PASSWORD);
    ResponseEntity<Map<String, Object>> mfa =
        mfa((String) login.getBody().get("challengeToken"), code(operator));

    assertThat(mfa.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(mfa.getBody()).containsEntry("id", operator.id().toString());
    assertThat(mfa.getBody()).containsEntry("username", operator.username());
    assertThat(roles(mfa)).containsExactly("RECONCILER", "REFUND_OPERATOR");

    String setCookie = mfa.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie)
        .contains(OperatorSessionService.COOKIE_NAME + "=")
        .contains("HttpOnly", "SameSite=Strict", "Path=/operator", "Secure");
    String token = sessionToken(mfa);

    byte[] storedHash =
        jdbc.queryForObject(
            "select token_hash from operator_sessions where operator_id = ?",
            byte[].class,
            operator.id());
    assertThat(storedHash).isEqualTo(sha256(token));
    assertThat(new String(storedHash, StandardCharsets.UTF_8)).isNotEqualTo(token);
    assertThat(count("select count(*) from operator_sessions where operator_id = ?", operator.id()))
        .isEqualTo(1);
    assertThat(auditCount(operator.id(), "MFA_SUCCEEDED")).isEqualTo(1);

    ResponseEntity<Map<String, Object>> me = me(token);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(me.getBody()).containsEntry("username", operator.username());
    assertThat(roles(me)).containsExactly("RECONCILER", "REFUND_OPERATOR");
    assertThat(me.getBody()).doesNotContainKeys("password", "totpSecret", "phone");
  }

  @Test
  void replayedTotpCodeCannotStartASecondSession() {
    TestOperator operator =
        createOperator("refund.four", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    String code = code(operator);

    ResponseEntity<Map<String, Object>> firstLogin = login(operator.username(), PASSWORD);
    assertThat(mfa((String) firstLogin.getBody().get("challengeToken"), code).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<Map<String, Object>> secondLogin = login(operator.username(), PASSWORD);
    ResponseEntity<Map<String, Object>> replayed =
        mfa((String) secondLogin.getBody().get("challengeToken"), code);

    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(replayed.getBody()).containsEntry("code", "INVALID_CODE");
    assertThat(count("select count(*) from operator_sessions where operator_id = ?", operator.id()))
        .isEqualTo(1);
    assertThat(auditCount(operator.id(), "MFA_FAILED")).isGreaterThanOrEqualTo(1);
  }

  @Test
  void logoutRevokesTheSessionAndClearsTheCookie() {
    TestOperator operator =
        createOperator("refund.five", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    String token = signIn(operator);

    ResponseEntity<Void> logout =
        rest.exchange(
            "/operator/auth/logout",
            HttpMethod.POST,
            new HttpEntity<>(cookieHeaders(token)),
            Void.class);

    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(logout.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .contains(OperatorSessionService.COOKIE_NAME + "=", "Max-Age=0", "Path=/operator");
    assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            jdbc.queryForObject(
                "select revoked_at is not null from operator_sessions where operator_id = ?",
                Boolean.class,
                operator.id()))
        .isTrue();
  }

  @Test
  void expiredMfaChallengeIsRejected() {
    TestOperator operator =
        createOperator("refund.six", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    ResponseEntity<Map<String, Object>> login = login(operator.username(), PASSWORD);
    jdbc.update(
        """
        update operator_mfa_challenges
        set created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute'
        where operator_id = ?
        """,
        operator.id());

    ResponseEntity<Map<String, Object>> mfa =
        mfa((String) login.getBody().get("challengeToken"), code(operator));

    assertThat(mfa.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(mfa.getBody()).containsEntry("code", "MFA_CHALLENGE_EXPIRED");
    assertThat(count("select count(*) from operator_sessions where operator_id = ?", operator.id()))
        .isZero();
  }

  @Test
  void idleSessionIsRejectedAndRevoked() {
    TestOperator operator =
        createOperator("refund.seven", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    String token = signIn(operator);
    jdbc.update(
        "update operator_sessions set last_seen_at = now() - interval '31 minutes' where operator_id = ?",
        operator.id());

    assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            jdbc.queryForObject(
                "select revoked_at is not null from operator_sessions where operator_id = ?",
                Boolean.class,
                operator.id()))
        .isTrue();
  }

  @Test
  void disabledOperatorCannotLogin() {
    TestOperator operator =
        createOperator("refund.eight", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    jdbc.update("update operators set status = 'DISABLED' where id = ?", operator.id());

    ResponseEntity<Map<String, Object>> login = login(operator.username(), PASSWORD);

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(login.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    assertThat(
            count(
                "select count(*) from operator_mfa_challenges where operator_id = ?",
                operator.id()))
        .isZero();
  }

  @Test
  void organizerAndOperatorSessionsAreNotInterchangeable() {
    TestOperator operator =
        createOperator("refund.nine", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    String token = signIn(operator);

    assertThat(
            rest.exchange(
                    "/organizer/me",
                    HttpMethod.GET,
                    new HttpEntity<>(cookieHeaders(token)),
                    new ParameterizedTypeReference<Map<String, Object>>() {})
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(me("organizer-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void repeatedWrongTotpCodesConsumeTheChallengeAndLockTheOperator() {
    TestOperator operator =
        createOperator("refund.ten", OperatorStatus.ACTIVE, OperatorRole.REFUND_OPERATOR);
    String challenge =
        (String) login(operator.username(), PASSWORD).getBody().get("challengeToken");

    for (int attempt = 0; attempt < 5; attempt++) {
      ResponseEntity<Map<String, Object>> response = mfa(challenge, "000000");
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    assertThat(
            jdbc.queryForObject(
                "select consumed_at is not null from operator_mfa_challenges where operator_id = ?",
                Boolean.class,
                operator.id()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select locked_until is not null from operators where id = ?",
                Boolean.class,
                operator.id()))
        .isTrue();
    assertThat(auditCount(operator.id(), "MFA_FAILED")).isEqualTo(5);
  }

  private TestOperator createOperator(
      String username, OperatorStatus status, OperatorRole... roles) {
    byte[] secret = new byte[20];
    new SecureRandom().nextBytes(secret);
    String encodedSecret = Base32.encode(secret);
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into operators
            (id, username, password_hash, totp_secret_ciphertext, status, created_at, updated_at)
        values (?, ?, ?, ?, ?, now(), now())
        """,
        id,
        username,
        hasher.hash(PASSWORD),
        cipher.encrypt(encodedSecret),
        status.name());
    for (OperatorRole role : roles) {
      jdbc.update(
          """
          insert into operator_roles (operator_id, role, granted_at)
          values (?, ?, now())
          """,
          id,
          role.name());
    }
    return new TestOperator(id, username, encodedSecret);
  }

  private String signIn(TestOperator operator) {
    ResponseEntity<Map<String, Object>> login = login(operator.username(), PASSWORD);
    ResponseEntity<Map<String, Object>> mfa =
        mfa((String) login.getBody().get("challengeToken"), code(operator));
    assertThat(mfa.getStatusCode()).isEqualTo(HttpStatus.OK);
    return sessionToken(mfa);
  }

  private ResponseEntity<Map<String, Object>> login(String username, String password) {
    return rest.exchange(
        "/operator/auth/login",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("username", username, "password", password), jsonHeaders(null)),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> mfa(String challengeToken, String code) {
    return rest.exchange(
        "/operator/auth/mfa",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("challengeToken", challengeToken, "code", code), jsonHeaders(null)),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Map<String, Object>> me(String token) {
    return rest.exchange(
        "/operator/me",
        HttpMethod.GET,
        new HttpEntity<>(cookieHeaders(token)),
        new ParameterizedTypeReference<>() {});
  }

  private static HttpHeaders cookieHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, OperatorSessionService.COOKIE_NAME + "=" + token);
    return headers;
  }

  private static HttpHeaders jsonHeaders(String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
    return headers;
  }

  @SuppressWarnings("unchecked")
  private static List<String> roles(ResponseEntity<Map<String, Object>> response) {
    return (List<String>) response.getBody().get("roles");
  }

  private static String sessionToken(ResponseEntity<?> response) {
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).isNotNull();
    String prefix = OperatorSessionService.COOKIE_NAME + "=";
    int start = setCookie.indexOf(prefix);
    assertThat(start).isGreaterThanOrEqualTo(0);
    int end = setCookie.indexOf(';', start);
    return setCookie.substring(start + prefix.length(), end < 0 ? setCookie.length() : end);
  }

  private static String code(TestOperator operator) {
    return Totp.code(Base32.decode(operator.secretBase32()), Instant.now());
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private long auditCount(UUID operatorId, String event) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from operator_auth_events where operator_id = ? and event = ?",
            Long.class,
            operatorId,
            event);
    return count == null ? 0 : count;
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
