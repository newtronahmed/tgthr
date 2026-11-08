package com.groupcollections.goal;

import com.groupcollections.payments.Network;

/** Resolves a MoMo account name before a goal can lock its payout destination. */
public interface AccountResolver {

  String resolve(String momoNumber, Network network);
}
