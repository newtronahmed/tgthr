package com.groupcollections.operator;

/** Operator auth failures. Messages are deliberately generic to avoid account enumeration. */
public final class OperatorException extends RuntimeException {

  private final int status;
  private final String code;
  private final String title;

  private OperatorException(int status, String code, String title, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
    this.title = title;
  }

  public static OperatorException invalidCredentials() {
    return new OperatorException(
        401, "INVALID_CREDENTIALS", "Sign-in failed", "Invalid credentials");
  }

  public static OperatorException unauthorized() {
    return new OperatorException(
        401, "OPERATOR_UNAUTHORIZED", "Operator sign-in required", "Sign in as an operator");
  }

  public static OperatorException forbidden() {
    return new OperatorException(403, "FORBIDDEN", "Forbidden", "The operator lacks this role");
  }

  public static OperatorException mfaExpired() {
    return new OperatorException(
        401, "MFA_CHALLENGE_EXPIRED", "Sign-in failed", "The MFA challenge expired");
  }

  public static OperatorException invalidCode() {
    return new OperatorException(
        401, "INVALID_CODE", "Sign-in failed", "Invalid authentication code");
  }

  public static OperatorException rateLimited() {
    return new OperatorException(429, "RATE_LIMITED", "Too many attempts", "Try again later");
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String title() {
    return title;
  }
}
