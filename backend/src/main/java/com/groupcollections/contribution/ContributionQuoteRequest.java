package com.groupcollections.contribution;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ContributionQuoteRequest(@NotNull @Min(1) @Max(10_000_000) Long amountPesewas) {}
