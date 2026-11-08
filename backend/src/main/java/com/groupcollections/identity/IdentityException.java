package com.groupcollections.identity;

/** Domain failures with stable HTTP codes from the FEAT-003 spec. */
public final class IdentityException extends RuntimeException {

  private final int status;
  private final String title;
  private final String code;

  private IdentityException(int status, String title, String code, String detail) {
    super(detail);
    this.status = status;
    this.title = title;
    this.code = code;
  }

  public static IdentityException invalidPhone() {
    return new IdentityException(
        422, "Invalid request", "INVALID_REQUEST", "Enter a Ghanaian mobile number");
  }

  public static IdentityException invalidOtp() {
    return new IdentityException(
        401, "Invalid code", "INVALID_OTP", "The code is invalid or expired");
  }

  public static IdentityException resendTooSoon() {
    return new IdentityException(
        429, "Too many requests", "RESEND_TOO_SOON", "Wait before requesting another code");
  }

  public static IdentityException rateLimited() {
    return new IdentityException(
        429, "Too many requests", "RATE_LIMITED", "Too many attempts; try again later");
  }

  public static IdentityException smsUnavailable() {
    return new IdentityException(
        503, "SMS unavailable", "SMS_UNAVAILABLE", "The code could not be sent; try again later");
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
