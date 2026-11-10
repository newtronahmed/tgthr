package com.groupcollections.payout;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payout-approvals")
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PayoutApprovalController {

  private final PayoutApprovalService approvals;
  private final PayoutApprovalRateLimiter rateLimiter;

  public PayoutApprovalController(
      PayoutApprovalService approvals, PayoutApprovalRateLimiter rateLimiter) {
    this.approvals = approvals;
    this.rateLimiter = rateLimiter;
  }

  @GetMapping("/{token}")
  public PayoutApprovalView view(@PathVariable String token, HttpServletRequest servletRequest) {
    rateLimiter.checkIp(servletRequest.getRemoteAddr());
    byte[] approverKey = approvals.rateLimitKey(token);
    if (approverKey != null) {
      rateLimiter.checkApprover(approverKey);
    }
    return approvals.view(token);
  }

  @PostMapping("/{token}/verify")
  public PayoutView verify(
      @PathVariable String token,
      @Valid @RequestBody PayoutApprovalVerify request,
      HttpServletRequest servletRequest) {
    rateLimiter.checkIp(servletRequest.getRemoteAddr());
    byte[] approverKey = approvals.rateLimitKey(token);
    if (approverKey != null) {
      rateLimiter.checkApprover(approverKey);
    }
    return approvals.verify(token, request.code());
  }

  @PostMapping("/{token}/resend")
  public ResponseEntity<Void> resend(
      @PathVariable String token, HttpServletRequest servletRequest) {
    rateLimiter.checkIp(servletRequest.getRemoteAddr());
    byte[] approverKey = approvals.rateLimitKey(token);
    if (approverKey != null) {
      rateLimiter.checkApprover(approverKey);
    }
    approvals.resend(token);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  @PostMapping("/{token}/decline")
  public PayoutView decline(@PathVariable String token, HttpServletRequest servletRequest) {
    rateLimiter.checkIp(servletRequest.getRemoteAddr());
    byte[] approverKey = approvals.rateLimitKey(token);
    if (approverKey != null) {
      rateLimiter.checkApprover(approverKey);
    }
    return approvals.decline(token);
  }
}
