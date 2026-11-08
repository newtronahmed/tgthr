package com.groupcollections.identity;

import java.time.Instant;
import java.util.UUID;

public record OrganizerProfile(UUID id, String displayName, String legalName, Instant createdAt) {

  public static OrganizerProfile from(OrganizerUser user) {
    return new OrganizerProfile(user.id(), user.displayName(), user.legalName(), user.createdAt());
  }
}
