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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operator/reconciliation")
@ConditionalOnProperty(name = "collections.refunds.enabled", havingValue = "true")
public class ReconciliationController {

  private final ReconciliationService reconciliations;

  public ReconciliationController(ReconciliationService reconciliations) {
    this.reconciliations = reconciliations;
  }

  @GetMapping("/pending")
  public ResponseEntity<List<ReconciliationItemView>> pending(HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(reconciliations.pending(principal(servletRequest)));
  }

  @PostMapping("/{contributionId}/reclassify")
  public ResponseEntity<ReconciliationResolutionView> reclassify(
      @PathVariable UUID contributionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReconciliationResolutionRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            reconciliations.reclassify(
                principal(servletRequest), contributionId, idempotencyKey, request.reason()));
  }

  @PostMapping("/{contributionId}/refund")
  public ResponseEntity<RefundView> refund(
      @PathVariable UUID contributionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReconciliationResolutionRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            reconciliations.refund(
                principal(servletRequest), contributionId, idempotencyKey, request.reason()));
  }

  @PostMapping("/{contributionId}/income")
  public ResponseEntity<ReconciliationResolutionView> proposeIncome(
      @PathVariable UUID contributionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody ReconciliationResolutionRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            reconciliations.proposeIncome(
                principal(servletRequest), contributionId, idempotencyKey, request.reason()));
  }

  @PostMapping("/{contributionId}/income/approve")
  public ResponseEntity<ReconciliationResolutionView> approveIncome(
      @PathVariable UUID contributionId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Operator-TOTP", required = false) String totpCode,
      @RequestHeader(value = "X-Operator-Recovery-Code", required = false) String recoveryCode,
      @Valid @RequestBody ReconciliationResolutionRequest request,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            reconciliations.approveIncome(
                principal(servletRequest),
                contributionId,
                idempotencyKey,
                request.reason(),
                totpCode,
                recoveryCode));
  }

  private static OperatorPrincipal principal(HttpServletRequest request) {
    Object principal = request.getAttribute(OperatorAuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal instanceof OperatorPrincipal operator) {
      return operator;
    }
    throw OperatorException.unauthorized();
  }
}
