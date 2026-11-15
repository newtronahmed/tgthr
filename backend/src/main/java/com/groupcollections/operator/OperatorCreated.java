package com.groupcollections.operator;

import java.time.Instant;
import java.util.UUID;

public record OperatorCreated(UUID id, String username, String enrollmentToken, Instant expiresAt) {

  @Override
  public String toString() {
    return "OperatorCreated[id="
        + id
        + ", username="
        + username
        + ", enrollmentToken=[REDACTED], expiresAt="
        + expiresAt
        + "]";
  }
}
