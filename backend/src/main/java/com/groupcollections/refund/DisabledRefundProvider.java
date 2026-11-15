package com.groupcollections.refund;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "collections.refunds.provider",
    havingValue = "none",
    matchIfMissing = true)
public class DisabledRefundProvider implements RefundProvider {

  @Override
  public RefundResult refund(Refund refund) {
    return RefundResult.failed("PROVIDER_DISABLED");
  }

  @Override
  public RefundResult verify(Refund refund) {
    return RefundResult.failed("PROVIDER_DISABLED");
  }
}
