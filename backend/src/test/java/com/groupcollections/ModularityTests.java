package com.groupcollections;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Enforces module boundaries (ADR 0001, Constitution VII.2). Keep this in the required CI checks. */
class ModularityTests {

  @Test
  void modulesRespectTheirBoundaries() {
    ApplicationModules.of(CollectionsApplication.class).verify();
  }
}
