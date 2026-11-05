package com.groupcollections.goal;

/** A submitted amount does not satisfy the goal's amount policy (SPEC FEAT-001 rule 2). */
public final class AmountValidationException extends RuntimeException {

  private final String code;

  public AmountValidationException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** Stable machine-readable code returned to the client, such as {@code AMOUNT_TOO_LOW}. */
  public String code() {
    return code;
  }
}
