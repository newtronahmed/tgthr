package com.groupcollections.refund;

/** Refund failures. Messages are operator-facing and never include phone numbers. */
public final class RefundException extends RuntimeException {

  private final int status;
  private final String code;
  private final String title;

  private RefundException(int status, String code, String title, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
    this.title = title;
  }

  public static RefundException notFound() {
    return new RefundException(404, "REFUND_NOT_FOUND", "Not found", "Refund not found");
  }

  public static RefundException contributionNotFound() {
    return new RefundException(
        404, "CONTRIBUTION_NOT_FOUND", "Not found", "Contribution not found");
  }

  public static RefundException notEligible() {
    return new RefundException(
        409, "REFUND_NOT_ELIGIBLE", "Refund not allowed", "The contribution is not eligible");
  }

  public static RefundException suspenseRequiresReconciliation() {
    return new RefundException(
        409,
        "SUSPENSE_REQUIRES_RECONCILIATION",
        "Refund not allowed",
        "Resolve this suspense item through reconciliation");
  }

  public static RefundException payoutInFlight(String payoutId, String state) {
    return new RefundException(
        409, "PAYOUT_IN_FLIGHT", "Refund blocked", "Payout " + payoutId + " is " + state);
  }

  public static RefundException afterPayout() {
    return new RefundException(
        409,
        "REFUND_AFTER_PAYOUT",
        "Refund blocked",
        "The goal has a completed payout; recovery is a P2 workflow");
  }

  public static RefundException inProgress() {
    return new RefundException(
        409, "REFUND_IN_PROGRESS", "Refund in progress", "Another refund is already active");
  }

  public static RefundException idempotencyConflict() {
    return new RefundException(
        409,
        "IDEMPOTENCY_CONFLICT",
        "Idempotency conflict",
        "The same idempotency key was used with a different reason");
  }

  public static RefundException notPending() {
    return new RefundException(
        409,
        "REFUND_NOT_PENDING",
        "Refund not pending",
        "Only a provider-pending refund can be verified");
  }

  public static RefundException invalid(String detail) {
    return new RefundException(422, "INVALID_REFUND", "Invalid refund", detail);
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
