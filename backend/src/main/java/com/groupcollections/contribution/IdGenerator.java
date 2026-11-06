package com.groupcollections.contribution;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Seam for contribution ids so tests can control ordering. */
@Component
public class IdGenerator {

  public UUID next() {
    return UUID.randomUUID();
  }
}
