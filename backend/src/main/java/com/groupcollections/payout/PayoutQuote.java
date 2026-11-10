package com.groupcollections.payout;

/** Quote for a requested all-in amount; no payout is created. */
public record PayoutQuote(
    long amountPesewas, long feePesewas, long netPesewas, long availablePesewas, String currency) {}
