package com.groupcollections.contribution;

import com.groupcollections.ledger.Money;
import java.util.UUID;

/** The contribution fields a callback needs to apply its result. */
public record ContributionPayment(UUID id, Money total) {}
