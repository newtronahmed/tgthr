package com.groupcollections.contribution;

/** A legal state change; the caller appends exactly one history row for it (INV-STATE-003). */
public record ContributionTransition(
    ContributionState from, ContributionState to, ContributionEvent event) {}
