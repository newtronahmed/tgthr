package com.groupcollections.payout;

/** Stable payout request failures. */
public final class PayoutException extends RuntimeException {

  private final int status;
  private final String title;
  private final String code;

  private PayoutException(int status, String title, String code, String detail) {
    super(detail);
    this.status = status;
    this.title = title;
    this.code = code;
  }

  public static PayoutException invalid(String detail) {
    return new PayoutException(422, "Invalid request", "INVALID_REQUEST", detail);
  }

  public static PayoutException approverRequired() {
    return new PayoutException(
        422,
        "Approver required",
        "APPROVER_REQUIRED",
        "Set a second approver before requesting a payout");
  }

  public static PayoutException insufficientSettledBalance() {
    return new PayoutException(
        422,
        "Insufficient settled balance",
        "INSUFFICIENT_SETTLED_BALANCE",
        "The goal does not have enough settled, unreserved funds for this payout");
  }

  public static PayoutException amountTooLow() {
    return new PayoutException(
        422, "Amount too low", "AMOUNT_TOO_LOW", "The payout amount must exceed the provider fee");
  }

  public static PayoutException idempotencyConflict() {
    return new PayoutException(
        409,
        "Idempotency conflict",
        "IDEMPOTENCY_CONFLICT",
        "The idempotency key was already used with a different amount");
  }

  public static PayoutException holdRequired() {
    return new PayoutException(
        422,
        "Hold required",
        "HOLD_REQUIRED",
        "The goal needs a hold window of at least one minute before requesting a payout");
  }

  public static PayoutException approvalInvalid() {
    return new PayoutException(
        401,
        "Invalid approval",
        "APPROVAL_INVALID",
        "The approval link or code is invalid, expired or already used");
  }

  public static PayoutException resendTooSoon() {
    return new PayoutException(
        429,
        "Too many requests",
        "RESEND_TOO_SOON",
        "Wait before requesting another approval code");
  }

  public static PayoutException goalFrozen() {
    return new PayoutException(
        409, "Goal frozen", "GOAL_FROZEN", "A frozen goal cannot start a new payout");
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }

  public String code() {
    return code;
  }
}
