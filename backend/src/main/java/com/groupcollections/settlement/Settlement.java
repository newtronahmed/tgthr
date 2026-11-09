package com.groupcollections.settlement;

import com.groupcollections.ledger.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record Settlement(
    UUID id,
    SettlementProvider provider,
    String providerSettlementReference,
    LocalDate settlementDate,
    Money gross,
    Money fees,
    Money net,
    SettlementDirection direction,
    SettlementStatus status,
    Instant receivedAt,
    Instant createdAt,
    Instant updatedAt) {

  public Settlement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(providerSettlementReference, "providerSettlementReference");
    Objects.requireNonNull(settlementDate, "settlementDate");
    Objects.requireNonNull(gross, "gross");
    Objects.requireNonNull(fees, "fees");
    Objects.requireNonNull(net, "net");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(status, "status");
    if (gross.minorUnits() <= 0 || net.minorUnits() <= 0) {
      throw new IllegalArgumentException("settlement gross and net must be positive");
    }
    if (!gross.minus(fees).equals(net)) {
      throw new IllegalArgumentException("settlement net must equal gross minus fees");
    }
    if (status == SettlementStatus.SETTLED && receivedAt == null) {
      throw new IllegalArgumentException("a SETTLED batch needs received_at");
    }
  }
}
