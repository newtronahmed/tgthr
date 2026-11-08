package com.groupcollections.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects cross-site state changes, complementing the SameSite=Lax session cookie. */
@Component
@Order(10)
public class SameOriginFilter extends OncePerRequestFilter {

  private final String allowedOrigin;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meters;

  public SameOriginFilter(
      @Value("${collections.web.origin:http://localhost:3000}") String allowedOrigin,
      ObjectMapper objectMapper,
      MeterRegistry meters) {
    this.allowedOrigin = allowedOrigin;
    this.objectMapper = objectMapper;
    this.meters = meters;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String method = request.getMethod();
    if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
      return true;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.equals("/auth/otp/request")
        && !path.equals("/auth/otp/verify")
        && !path.equals("/auth/logout")
        && !path.equals("/organizer")
        && !path.startsWith("/organizer/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    String fetchSite = request.getHeader("Sec-Fetch-Site");
    boolean crossSiteFetch = "cross-site".equalsIgnoreCase(fetchSite);
    boolean foreignOrigin = origin != null && !origin.equals(allowedOrigin);
    if (crossSiteFetch || foreignOrigin) {
      meters.counter("identity.cross_site.rejected").increment();
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      objectMapper.writeValue(
          response.getOutputStream(),
          Map.of(
              "type",
              "about:blank",
              "title",
              "Forbidden",
              "status",
              403,
              "detail",
              "Cross-site request rejected",
              "code",
              "CROSS_SITE_REQUEST"));
      return;
    }
    chain.doFilter(request, response);
  }
}
