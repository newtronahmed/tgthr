package com.groupcollections.goal;

import com.groupcollections.payments.Network;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateGoalRequest(
    @NotBlank @Size(min = 3, max = 120) String title,
    @NotNull GoalTemplate template,
    @NotNull TargetType targetType,
    @Min(1) Long fixedAmountPesewas,
    @Min(1) Long targetAmountPesewas,
    @Min(1000) Long minimumAmountPesewas,
    @NotNull Instant deadline,
    @NotNull DestinationType destinationType,
    @NotBlank @Pattern(regexp = "^(\\+233|0)[0-9]{9}$") String destinationPhone,
    @NotNull Network network,
    @NotNull SecondApprover secondApprover,
    @Size(max = 120) String secondApproverName,
    @Pattern(regexp = "^(\\+233|0)[0-9]{9}$") String secondApproverPhone,
    @Min(0) Long secondApproverThresholdPesewas,
    @Min(0) @Max(1440) Integer holdWindowMinutes,
    SurplusRule surplusRule) {

  @Override
  public String toString() {
    return "CreateGoalRequest[title="
        + title
        + ", template="
        + template
        + ", targetType="
        + targetType
        + ", destinationType="
        + destinationType
        + ", destinationPhone=redacted, network="
        + network
        + "]";
  }
}
