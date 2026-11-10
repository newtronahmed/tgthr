package com.groupcollections.payout;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** All-in goal-pool debit, in integer pesewas. */
public record PayoutRequest(@NotNull @Min(1) Long amountPesewas) {}
