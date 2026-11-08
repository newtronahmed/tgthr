package com.groupcollections.identity;

import java.time.Instant;
import java.util.UUID;

public record OrganizerUser(UUID id, String displayName, String legalName, Instant createdAt) {}
