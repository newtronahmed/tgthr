package com.groupcollections.contribution;

import java.util.UUID;

public final class ContributionNotFoundException extends RuntimeException {

  public ContributionNotFoundException(UUID id) {
    super("no contribution with id " + id);
  }
}
