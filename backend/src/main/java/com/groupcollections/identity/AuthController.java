package com.groupcollections.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  private final OtpService otp;
  private final SessionService sessions;
  private final boolean cookieSecure;

  public AuthController(
      OtpService otp,
      SessionService sessions,
      @Value("${collections.security.cookie-secure:true}") boolean cookieSecure) {
    this.otp = otp;
    this.sessions = sessions;
    this.cookieSecure = cookieSecure;
  }

  @PostMapping("/auth/otp/request")
  public ResponseEntity<Void> request(
      @Valid @RequestBody OtpRequest request, HttpServletRequest servletRequest) {
    otp.request(request.phone(), servletRequest.getRemoteAddr());
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/auth/otp/verify")
  public ResponseEntity<OrganizerProfile> verify(
      @Valid @RequestBody OtpVerify request, HttpServletRequest servletRequest) {
    OtpService.VerifiedOrganizer verified =
        otp.verify(
            request.phone(),
            request.code(),
            request.displayName(),
            request.legalName(),
            servletRequest.getRemoteAddr());
    ResponseCookie cookie =
        ResponseCookie.from(SessionService.COOKIE_NAME, verified.token())
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path("/")
            .maxAge(sessions.sessionTtl())
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(OrganizerProfile.from(verified.user()));
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    sessions.revoke(cookie(request));
    ResponseCookie cleared =
        ResponseCookie.from(SessionService.COOKIE_NAME, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ZERO)
            .build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  private static String cookie(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return null;
    }
    for (Cookie cookie : request.getCookies()) {
      if (SessionService.COOKIE_NAME.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
