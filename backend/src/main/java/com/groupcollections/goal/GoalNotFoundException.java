package com.groupcollections.goal;

public final class GoalNotFoundException extends RuntimeException {

  public GoalNotFoundException(String slug) {
    super("no goal with slug " + slug);
  }
}
