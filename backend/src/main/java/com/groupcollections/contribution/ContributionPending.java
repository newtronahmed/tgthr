package com.groupcollections.contribution;

import java.util.UUID;

/** A PENDING contribution the timeout job has locked and may resolve. */
public record ContributionPending(UUID id, String providerReference) {}
