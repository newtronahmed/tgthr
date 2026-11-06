package com.groupcollections.contribution;

import java.time.Instant;
import java.util.UUID;

/** A contribution the timeout job may resolve. */
public record ContributionPending(UUID id, String providerReference, Instant updatedAt) {}
