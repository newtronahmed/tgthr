package com.groupcollections.goal;

import com.groupcollections.payments.Network;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test-only resolver for the browser E2E; gated by ACCOUNT_RESOLVER=dev. */
@Component
@ConditionalOnProperty(name = "collections.payments.account-resolver", havingValue = "dev")
@Profile("e2e")
public class DevAccountResolver implements AccountResolver {

  @Override
  public String resolve(String momoNumber, Network network) {
    return "Kofi Mensah";
  }
}
