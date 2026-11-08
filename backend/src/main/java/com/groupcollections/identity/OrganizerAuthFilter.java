package com.groupcollections.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the opaque session cookie for organizer endpoints and rejects anonymous requests. */
@Component
@Order(20)
public class OrganizerAuthFilter extends OncePerRequestFilter {

  public static final String USER_ATTRIBUTE = "organizerUser";

  private final SessionService sessions;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meters;

  public OrganizerAuthFilter(
      SessionService sessions, ObjectMapper objectMapper, MeterRegistry meters) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
    this.meters = meters;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.equals("/organizer") && !path.startsWith("/organizer/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<OrganizerUser> user = sessions.authenticate(cookie(request));
    if (user.isEmpty()) {
      meters.counter("identity.auth.rejected").increment();
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(),
          Map.of(
              "type",
              "about:blank",
              "title",
              "Unauthorized",
              "status",
              401,
              "detail",
              "Sign in required",
              "code",
              "UNAUTHENTICATED"));
      return;
    }
    request.setAttribute(USER_ATTRIBUTE, user.get());
    chain.doFilter(request, response);
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
