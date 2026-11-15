package com.groupcollections.refund;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationResolutionView(
    UUID contributionId, String resolution, String status, UUID postingSetId, Instant resolvedAt) {

  public static ReconciliationResolutionView from(ReconciliationResolution resolution) {
    return new ReconciliationResolutionView(
        resolution.contributionId(),
        resolution.resolution(),
        resolution.status(),
        resolution.postingSetId(),
        resolution.approvedAt() == null ? resolution.createdAt() : resolution.approvedAt());
  }
}
