package com.groupcollections.goal;

public final class GoalNotAcceptingException extends RuntimeException {

  public GoalNotAcceptingException(String slug) {
    super("goal is not accepting contributions: " + slug);
  }
}
