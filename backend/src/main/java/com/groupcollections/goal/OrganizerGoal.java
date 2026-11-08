package com.groupcollections.goal;

import java.time.Instant;
import java.util.UUID;

public record OrganizerGoal(
    UUID id,
    String slug,
    String title,
    GoalTemplate template,
    TargetType targetType,
    Long fixedAmountPesewas,
    Long targetAmountPesewas,
    Long minimumAmountPesewas,
    GoalStatus status,
    Instant deadline,
    DestinationType destinationType,
    String destinationAccountName,
    SecondApprover secondApprover,
    long secondApproverThresholdPesewas,
    int holdWindowMinutes,
    SurplusRule surplusRule,
    long raisedPesewas,
    long contributorCount,
    Instant createdAt) {}
