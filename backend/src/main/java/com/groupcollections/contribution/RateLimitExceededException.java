package com.groupcollections.contribution;

public final class RateLimitExceededException extends RuntimeException {

  public RateLimitExceededException() {
    super("too many contribution attempts; try again shortly");
  }
}
