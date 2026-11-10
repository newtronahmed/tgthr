package com.groupcollections.payout;

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

  public PayoutApprovalController(PayoutApprovalService approvals) {
    this.approvals = approvals;
  }

  @GetMapping("/{token}")
  public PayoutApprovalView view(@PathVariable String token) {
    return approvals.view(token);
  }

  @PostMapping("/{token}/verify")
  public PayoutView verify(
      @PathVariable String token, @Valid @RequestBody PayoutApprovalVerify request) {
    return approvals.verify(token, request.code());
  }

  @PostMapping("/{token}/resend")
  public ResponseEntity<Void> resend(@PathVariable String token) {
    approvals.resend(token);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  @PostMapping("/{token}/decline")
  public PayoutView decline(@PathVariable String token) {
    return approvals.decline(token);
  }
}
