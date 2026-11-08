package com.groupcollections.contribution;

import com.groupcollections.goal.Goal;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.GoalRepository;
import com.groupcollections.ledger.Money;
import org.springframework.stereotype.Service;

@Service
public class ContributionQuoteService {

  private final GoalRepository goals;
  private final FeeCalculator fees;

  public ContributionQuoteService(GoalRepository goals, FeeCalculator fees) {
    this.goals = goals;
    this.fees = fees;
  }

  public FeeQuote quote(String slug, long amountPesewas) {
    Goal goal = goals.findBySlug(slug).orElseThrow(() -> new GoalNotFoundException(slug));
    Money amount = Money.of(amountPesewas, "GHS");
    goal.rules().check(amount);
    FeeBreakdown breakdown = fees.calculate(amount);
    return new FeeQuote(
        breakdown.amount().minorUnits(),
        breakdown.processingFee().minorUnits(),
        breakdown.platformFee().minorUnits(),
        breakdown.total().minorUnits());
  }
}
