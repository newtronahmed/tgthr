package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
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
      "collections.operator.lockout=PT15M",
      "collections.operator.step-up-window=PT5M",
      "collections.operator.enrollment-ttl=PT1H"
    })
class OperatorAdminIT extends ApiIntegrationTest {

  private static final String PASSWORD = "correct horse battery staple";
  private static final String NEW_PASSWORD = "new operator password";

  @Autowired private PasswordHasher hasher;
  @Autowired private OperatorSecretCipher cipher;
  @Autowired private OperatorRepository operators;

  @Test
  void adminCreatesEnrollsAndActivatesAnOperator() {
    TestOperator admin = createActiveOperator("admin.one", OperatorRole.OPERATOR_ADMIN);
    String adminToken = signIn(admin);

    ResponseEntity<Map<String, Object>> created =
        post(
            "/operator/operators",
            Map.of("username", "refund.new", "roles", List.of("REFUND_OPERATOR")),
            adminToken,
            null);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(created.getBody()).containsKeys("id", "enrollmentToken", "expiresAt");
    assertThat(created.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    UUID operatorId = UUID.fromString((String) created.getBody().get("id"));
    String enrollmentToken = (String) created.getBody().get("enrollmentToken");
    assertThat(
            jdbc.queryForObject(
                "select status from operators where id = ?", String.class, operatorId))
        .isEqualTo("PENDING_ENROLLMENT");
    assertThat(
            count(
                "select count(*) from operator_roles where operator_id = ? and role = 'REFUND_OPERATOR'",
                operatorId))
        .isEqualTo(1);

    ResponseEntity<Map<String, Object>> enrolled =
        post(
            "/operator/auth/enroll",
            Map.of("token", enrollmentToken, "password", NEW_PASSWORD),
            null,
            null);
    assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(enrolled.getBody()).containsKeys("totpSecret", "otpauthUri", "recoveryCodes");
    assertThat((List<?>) enrolled.getBody().get("recoveryCodes")).hasSize(10);
    String secret = (String) enrolled.getBody().get("totpSecret");
    assertThat(
            jdbc.queryForObject(
                "select password_hash like 'pbkdf2-sha256$600000$%' from operators where id = ?",
                Boolean.class, operatorId))
        .isTrue();

    ResponseEntity<Void> confirmed =
        postVoid(
            "/operator/auth/enroll/confirm",
            Map.of(
                "token", enrollmentToken, "code", Totp.code(Base32.decode(secret), Instant.now())),
            null);
    assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(
            jdbc.queryForObject(
                "select status from operators where id = ?", String.class, operatorId))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "select consumed_at is not null from operator_enrollment_tokens where operator_id = ?",
                Boolean.class,
                operatorId))
        .isTrue();

    String recoveryCode = (String) ((List<?>) enrolled.getBody().get("recoveryCodes")).getFirst();
    ResponseEntity<Map<String, Object>> recovered =
        post(
            "/operator/auth/recovery",
            Map.of(
                "username", "refund.new",
                "password", NEW_PASSWORD,
                "recoveryCode", recoveryCode),
            null,
            null);
    assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(recovered.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .contains(OperatorSessionService.COOKIE_NAME + "=");
    assertThat(
            post(
                    "/operator/auth/recovery",
                    Map.of(
                        "username", "refund.new",
                        "password", NEW_PASSWORD,
                        "recoveryCode", recoveryCode),
                    null,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<Map<String, Object>> login = login("refund.new", NEW_PASSWORD);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<Map<String, Object>> mfa =
        mfa(
            (String) login.getBody().get("challengeToken"),
            Totp.code(Base32.decode(secret), Instant.now().plusSeconds(Totp.STEP_SECONDS)));
    assertThat(mfa.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(roles(mfa)).containsExactly("REFUND_OPERATOR");

    ResponseEntity<Map<String, Object>> reused =
        post(
            "/operator/auth/enroll",
            Map.of("token", enrollmentToken, "password", NEW_PASSWORD),
            null,
            null);
    assertThat(reused.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void nonAdminCannotCreateChangeRolesOrDisable() {
    TestOperator refund = createActiveOperator("refund.nonadmin", OperatorRole.REFUND_OPERATOR);
    String token = signIn(refund);

    assertThat(
            post(
                    "/operator/operators",
                    Map.of("username", "another.one", "roles", List.of("REFUND_OPERATOR")),
                    token,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            put(
                    "/operator/operators/" + refund.id() + "/roles",
                    Map.of("roles", List.of("RECONCILER")),
                    token,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            post("/operator/operators/" + refund.id() + "/disable", Map.of(), token, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void roleChangeRequiresFreshTotpWhenTheLoginIsStale() {
    TestOperator admin = createActiveOperator("admin.stepup", OperatorRole.OPERATOR_ADMIN);
    TestOperator target = createActiveOperator("refund.stepup", OperatorRole.REFUND_OPERATOR);
    String token = signIn(admin);
    jdbc.update("update operators set last_totp_step = -1 where id = ?", admin.id());

    ResponseEntity<Map<String, Object>> stale =
        put(
            "/operator/operators/" + target.id() + "/roles",
            Map.of("roles", List.of("RECONCILER")),
            token,
            null);
    assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(stale.getBody()).containsEntry("code", "STEP_UP_REQUIRED");
    assertThat(auditCount(admin.id(), "STEP_UP_REQUIRED")).isEqualTo(1);

    ResponseEntity<Map<String, Object>> stepped =
        put(
            "/operator/operators/" + target.id() + "/roles",
            Map.of("roles", List.of("RECONCILER")),
            token,
            code(admin));
    assertThat(stepped.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(roles(stepped)).containsExactly("RECONCILER");
  }

  @Test
  void disablingAnOperatorRevokesTheirSessions() {
    TestOperator admin = createActiveOperator("admin.disable", OperatorRole.OPERATOR_ADMIN);
    TestOperator target = createActiveOperator("refund.disable", OperatorRole.REFUND_OPERATOR);
    String adminToken = signIn(admin);
    String targetToken = signIn(target);

    ResponseEntity<Map<String, Object>> disabled =
        post("/operator/operators/" + target.id() + "/disable", Map.of(), adminToken, null);

    assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(disabled.getBody()).containsEntry("status", "DISABLED");
    assertThat(me(targetToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            jdbc.queryForObject(
                "select status from operators where id = ?", String.class, target.id()))
        .isEqualTo("DISABLED");
  }

  @Test
  void listNeverExposesSecrets() {
    TestOperator admin = createActiveOperator("admin.list", OperatorRole.OPERATOR_ADMIN);
    TestOperator target = createActiveOperator("refund.list", OperatorRole.REFUND_OPERATOR);
    String token = signIn(admin);

    ResponseEntity<String> list =
        rest.exchange(
            "/operator/operators",
            HttpMethod.GET,
            new HttpEntity<>(cookieHeaders(token)),
            String.class);

    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).contains(target.username());
    assertThat(list.getBody()).doesNotContain("password", "totp", "phone", "secret");
  }

  @Test
  void bootstrapLockRunsOnPostgres() {
    operators.lockBootstrap();
  }

  private TestOperator createActiveOperator(String username, OperatorRole... roles) {
    byte[] secret = new byte[20];
    new SecureRandom().nextBytes(secret);
    String encodedSecret = Base32.encode(secret);
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into operators
            (id, username, password_hash, totp_secret_ciphertext, status, created_at, updated_at)
        values (?, ?, ?, ?, 'ACTIVE', now(), now())
        """,
        id,
        username,
        hasher.hash(PASSWORD),
        cipher.encrypt(encodedSecret));
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

  private ResponseEntity<Map<String, Object>> post(
      String path, Map<String, Object> body, String token, String totpCode) {
    HttpHeaders headers = jsonHeaders(token);
    if (totpCode != null) {
      headers.add("X-Operator-TOTP", totpCode);
    }
    return rest.exchange(
        path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<Void> postVoid(String path, Map<String, Object> body, String token) {
    return rest.exchange(
        path, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(token)), Void.class);
  }

  private ResponseEntity<Map<String, Object>> put(
      String path, Map<String, Object> body, String token, String totpCode) {
    HttpHeaders headers = jsonHeaders(token);
    if (totpCode != null) {
      headers.add("X-Operator-TOTP", totpCode);
    }
    return rest.exchange(
        path,
        HttpMethod.PUT,
        new HttpEntity<>(body, headers),
        new ParameterizedTypeReference<>() {});
  }

  private static HttpHeaders jsonHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.add(HttpHeaders.COOKIE, OperatorSessionService.COOKIE_NAME + "=" + token);
    }
    return headers;
  }

  private static HttpHeaders cookieHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, OperatorSessionService.COOKIE_NAME + "=" + token);
    return headers;
  }

  private static String sessionToken(ResponseEntity<?> response) {
    String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(setCookie).isNotNull();
    String prefix = OperatorSessionService.COOKIE_NAME + "=";
    int start = setCookie.indexOf(prefix);
    int end = setCookie.indexOf(';', start);
    return setCookie.substring(start + prefix.length(), end < 0 ? setCookie.length() : end);
  }

  @SuppressWarnings("unchecked")
  private static List<String> roles(ResponseEntity<Map<String, Object>> response) {
    return (List<String>) response.getBody().get("roles");
  }

  private static String code(TestOperator operator) {
    return Totp.code(Base32.decode(operator.secretBase32()), Instant.now());
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
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

  private record TestOperator(UUID id, String username, String secretBase32) {}
}
