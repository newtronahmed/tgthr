package com.groupcollections.goal;

import com.groupcollections.payments.Network;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Until the Paystack MoMo resolution spike lands (T001), production refuses to guess a name. */
@Component
@ConditionalOnProperty(
    name = "collections.payments.account-resolver",
    havingValue = "none",
    matchIfMissing = true)
public class UnconfiguredAccountResolver implements AccountResolver {

  @Override
  public String resolve(String momoNumber, Network network) {
    throw OrganizerGoalException.destinationUnavailable();
  }
}
