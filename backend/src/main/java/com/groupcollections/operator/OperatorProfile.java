package com.groupcollections.operator;

import java.util.List;
import java.util.UUID;

/** Operator profile. Never contains a phone number, password hash or TOTP secret. */
public record OperatorProfile(UUID id, String username, List<String> roles) {

  public static OperatorProfile from(OperatorPrincipal principal) {
    return new OperatorProfile(
        principal.id(),
        principal.username(),
        principal.roles().stream().map(Enum::name).sorted().toList());
  }
}
