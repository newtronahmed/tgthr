package com.groupcollections.settlement;

import com.groupcollections.ledger.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SettlementItem(
    UUID id,
    UUID settlementId,
    UUID contributionId,
    SettlementItemType itemType,
    SettlementDirection direction,
    Money gross,
    Money fee,
    Money net,
    Money goalAmount,
    Instant createdAt) {

  public SettlementItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(settlementId, "settlementId");
    Objects.requireNonNull(itemType, "itemType");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(gross, "gross");
    Objects.requireNonNull(fee, "fee");
    Objects.requireNonNull(net, "net");
    Objects.requireNonNull(goalAmount, "goalAmount");
    if (!gross.minus(fee).equals(net)) {
      throw new IllegalArgumentException("settlement item net must equal gross minus fee");
    }
    if (!gross.currencyCode().equals(goalAmount.currencyCode())) {
      throw new IllegalArgumentException("goal amount currency must match the item");
    }
    if (goalAmount.minorUnits() > gross.minorUnits()) {
      throw new IllegalArgumentException("goal amount cannot exceed gross");
    }
    if (itemType == SettlementItemType.CONTRIBUTION && contributionId == null) {
      throw new IllegalArgumentException("a CONTRIBUTION item requires a contribution");
    }
  }
}
