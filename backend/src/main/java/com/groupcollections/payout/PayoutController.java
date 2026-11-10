package com.groupcollections.payout;

import com.groupcollections.identity.OrganizerAuthFilter;
import com.groupcollections.identity.OrganizerUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizer/goals/{goalId}")
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PayoutController {

  private final PayoutService payouts;
  private final PayoutApprovalService approvals;

  public PayoutController(PayoutService payouts, PayoutApprovalService approvals) {
    this.payouts = payouts;
    this.approvals = approvals;
  }

  @GetMapping("/payouts")
  public java.util.List<PayoutView> list(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @PathVariable UUID goalId) {
    return payouts.list(user, goalId);
  }

  @GetMapping("/payout-quote")
  public PayoutQuote quote(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @PathVariable UUID goalId,
      @RequestParam long amountPesewas) {
    return payouts.quote(user, goalId, amountPesewas);
  }

  @PostMapping("/payouts")
  public ResponseEntity<PayoutView> request(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @PathVariable UUID goalId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody PayoutRequest request) {
    PayoutView payout = payouts.request(user, goalId, idempotencyKey, request.amountPesewas());
    if (payout.state().equals("REQUESTED")) {
      approvals.sendForPayout(payout.id());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(payout);
  }
}
