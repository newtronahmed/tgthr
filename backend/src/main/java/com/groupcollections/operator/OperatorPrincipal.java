package com.groupcollections.operator;

import java.util.Set;
import java.util.UUID;

public record OperatorPrincipal(UUID id, String username, Set<OperatorRole> roles) {

  public OperatorPrincipal {
    roles = Set.copyOf(roles);
  }

  public boolean hasRole(OperatorRole role) {
    return roles.contains(role);
  }

  public void requireRole(OperatorRole role) {
    if (!hasRole(role)) {
      throw OperatorException.forbidden();
    }
  }
}
