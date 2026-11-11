package com.groupcollections.payout;

import com.groupcollections.identity.OrganizerAuthFilter;
import com.groupcollections.identity.OrganizerUser;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizer/payouts")
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PayoutCancelController {

  private final PayoutService payouts;

  public PayoutCancelController(PayoutService payouts) {
    this.payouts = payouts;
  }

  @PostMapping("/{id}/cancel")
  public PayoutView cancel(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @PathVariable UUID id) {
    return payouts.cancel(user, id);
  }
}
