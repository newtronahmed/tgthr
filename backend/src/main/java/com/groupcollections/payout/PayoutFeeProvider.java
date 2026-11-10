package com.groupcollections.payout;

import com.groupcollections.ledger.Money;
import java.util.UUID;

/** Quotes the provider transfer fee for an all-in payout amount. */
public interface PayoutFeeProvider {

  Money quote(UUID goalId, Money amount);
}
