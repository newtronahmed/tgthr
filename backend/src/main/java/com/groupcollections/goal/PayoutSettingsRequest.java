package com.groupcollections.goal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Changes to the approver and hold before the first payout. Phones are write-only. */
public record PayoutSettingsRequest(
    @Size(max = 120) String secondApproverName,
    @Pattern(regexp = "^(\\+233|0)[0-9]{9}$") String secondApproverPhone,
    @Min(0) @Max(1440) Integer holdWindowMinutes) {

  @Override
  public String toString() {
    return "PayoutSettingsRequest[secondApproverName="
        + secondApproverName
        + ", secondApproverPhone=redacted, holdWindowMinutes="
        + holdWindowMinutes
        + "]";
  }
}
