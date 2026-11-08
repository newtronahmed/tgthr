package com.groupcollections.contribution;

import com.groupcollections.ledger.Money;
import java.util.UUID;

/** The contribution amounts a callback needs to confirm and post. */
public record ContributionPayment(
    UUID id, UUID goalId, Money amount, Money processingFee, Money platformFee, Money total) {}
