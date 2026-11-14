package com.groupcollections.operator;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operator")
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorAuthController {

  private final OperatorAuthService auth;
  private final OperatorSessionService sessions;
  private final OperatorRateLimiter rateLimiter;
  private final Clock clock;
  private final boolean cookieSecure;

  public OperatorAuthController(
      OperatorAuthService auth,
      OperatorSessionService sessions,
      OperatorRateLimiter rateLimiter,
      Clock clock,
      @Value("${collections.security.cookie-secure:true}") boolean cookieSecure) {
    this.auth = auth;
    this.sessions = sessions;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.cookieSecure = cookieSecure;
  }

  @PostMapping("/auth/login")
  public ResponseEntity<OperatorLoginResponse> login(
      @Valid @RequestBody OperatorLoginRequest request, HttpServletRequest servletRequest) {
    rateLimiter.checkLogin(servletRequest.getRemoteAddr());
    OperatorAuthService.LoginChallenge challenge =
        auth.beginLogin(request.username(), request.password());
    return ResponseEntity.ok(
        new OperatorLoginResponse(challenge.challengeToken(), challenge.expiresAt()));
  }

  @PostMapping("/auth/mfa")
  public ResponseEntity<OperatorProfile> mfa(
      @Valid @RequestBody OperatorMfaRequest request, HttpServletRequest servletRequest) {
    rateLimiter.checkMfa(servletRequest.getRemoteAddr());
    OperatorAuthService.Authenticated authenticated =
        auth.verifyMfa(request.challengeToken(), request.code());
    ResponseCookie cookie =
        ResponseCookie.from(OperatorSessionService.COOKIE_NAME, authenticated.session().token())
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path(sessions.cookiePath())
            .maxAge(sessions.sessionTtl())
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(OperatorProfile.from(authenticated.principal()));
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    String token = cookie(request, OperatorSessionService.COOKIE_NAME);
    Object attribute = request.getAttribute(OperatorAuthFilter.PRINCIPAL_ATTRIBUTE);
    OperatorPrincipal principal = attribute instanceof OperatorPrincipal operator ? operator : null;
    if (principal == null && token != null) {
      principal = sessions.authenticate(token, Instant.now(clock)).orElse(null);
    }
    auth.logout(principal, token);
    ResponseCookie cleared =
        ResponseCookie.from(OperatorSessionService.COOKIE_NAME, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path(sessions.cookiePath())
            .maxAge(Duration.ZERO)
            .build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  @GetMapping("/me")
  public OperatorProfile me(HttpServletRequest request) {
    Object principal = request.getAttribute(OperatorAuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal instanceof OperatorPrincipal operator) {
      return OperatorProfile.from(operator);
    }
    throw OperatorException.unauthorized();
  }

  static String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() == null) {
      return null;
    }
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
