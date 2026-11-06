package com.groupcollections.contribution;

import com.groupcollections.goal.Goal;
import com.groupcollections.goal.GoalNotFoundException;
import com.groupcollections.goal.GoalRepository;
import org.springframework.stereotype.Service;

@Service
public class PublicGoalService {

  private static final int FEED_LIMIT = 20;

  private final GoalRepository goals;
  private final ContributionRepository contributions;

  public PublicGoalService(GoalRepository goals, ContributionRepository contributions) {
    this.goals = goals;
    this.contributions = contributions;
  }

  public PublicGoalView get(String slug) {
    Goal goal = goals.findBySlug(slug).orElseThrow(() -> new GoalNotFoundException(slug));
    return new PublicGoalView(
        goal.slug(),
        goal.title(),
        goal.template(),
        goal.targetType().name(),
        goal.fixedAmount() == null ? null : goal.fixedAmount().pesewas(),
        goal.targetAmount() == null ? null : goal.targetAmount().pesewas(),
        contributions.goalRaised(goal.id()).pesewas(),
        contributions.confirmedCount(goal.id()),
        goal.deadline(),
        goal.status().name(),
        new PublicGoalView.PayoutRules(
            goal.destinationType(),
            goal.secondApprover(),
            goal.holdWindowMinutes(),
            goal.surplusRule()),
        contributions.feed(goal.id(), FEED_LIMIT));
  }
}
