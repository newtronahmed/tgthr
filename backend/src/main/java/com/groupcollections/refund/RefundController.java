package com.groupcollections.refund;

import com.groupcollections.operator.OperatorAuthFilter;
import com.groupcollections.operator.OperatorException;
import com.groupcollections.operator.OperatorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operator")
@ConditionalOnProperty(name = "collections.refunds.enabled", havingValue = "true")
public class RefundController {

  private final RefundService refunds;

  public RefundController(RefundService refunds) {
    this.refunds = refunds;
  }

  @PostMapping("/contributions/{id}/refund")
  public ResponseEntity<RefundView> request(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody RefundRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(refunds.request(principal(servletRequest), id, idempotencyKey, request.reason()));
  }

  @GetMapping("/refunds/{id}")
  public ResponseEntity<RefundView> get(@PathVariable UUID id, HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(refunds.get(principal(servletRequest), id));
  }

  @GetMapping("/refunds")
  public List<RefundView> list(
      @RequestParam(value = "state", required = false) String state,
      HttpServletRequest servletRequest) {
    return refunds.list(principal(servletRequest), state);
  }

  @PostMapping("/refunds/{id}/verify")
  public ResponseEntity<RefundView> verify(
      @PathVariable UUID id,
      @Valid @RequestBody RefundVerifyRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(refunds.verify(principal(servletRequest), id, request.reason()));
  }

  private static OperatorPrincipal principal(HttpServletRequest request) {
    Object principal = request.getAttribute(OperatorAuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal instanceof OperatorPrincipal operator) {
      return operator;
    }
    throw OperatorException.unauthorized();
  }
}
