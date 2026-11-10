package com.groupcollections.payout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A contributor report about a specific payout; no phone is accepted or stored. */
public record PayoutReportRequest(@NotBlank @Size(max = 500) String reason, UUID contributionId) {}
