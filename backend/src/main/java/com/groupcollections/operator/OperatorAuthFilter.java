package com.groupcollections.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the operator cookie for /operator/** and rejects anonymous or expired sessions. */
@Component
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorAuthFilter extends OncePerRequestFilter {

  public static final String PRINCIPAL_ATTRIBUTE = OperatorPrincipal.class.getName();

  private final OperatorSessionService sessions;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public OperatorAuthFilter(
      OperatorSessionService sessions, ObjectMapper objectMapper, Clock clock) {
    this.sessions = sessions;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = path(request);
    if (!path.startsWith("/operator")) {
      return true;
    }
    return path.equals("/operator/auth/login")
        || path.equals("/operator/auth/mfa")
        || path.equals("/operator/auth/logout");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String token = OperatorAuthController.cookie(request, OperatorSessionService.COOKIE_NAME);
    Optional<OperatorPrincipal> principal = sessions.authenticate(token, Instant.now(clock));
    if (principal.isEmpty()) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in as an operator");
      problem.setTitle("Operator sign-in required");
      problem.setProperty("code", "OPERATOR_UNAUTHORIZED");
      objectMapper.writeValue(response.getOutputStream(), problem);
      return;
    }
    request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
    filterChain.doFilter(request, response);
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return context.isEmpty() ? uri : uri.substring(context.length());
  }
}
