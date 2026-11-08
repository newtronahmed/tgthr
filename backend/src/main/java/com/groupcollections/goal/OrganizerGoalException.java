package com.groupcollections.goal;

/** Destination resolution failures and goal validation errors with stable problem codes. */
public final class OrganizerGoalException extends RuntimeException {

  private final int status;
  private final String title;
  private final String code;

  private OrganizerGoalException(int status, String title, String code, String detail) {
    super(detail);
    this.status = status;
    this.title = title;
    this.code = code;
  }

  public static OrganizerGoalException invalid(String detail) {
    return new OrganizerGoalException(422, "Invalid request", "INVALID_REQUEST", detail);
  }

  public static OrganizerGoalException legalNameRequired() {
    return new OrganizerGoalException(
        422, "Legal name required", "LEGAL_NAME_REQUIRED", "Add your legal name first");
  }

  public static OrganizerGoalException destinationUnverified() {
    return new OrganizerGoalException(
        422,
        "Destination unverified",
        "DESTINATION_UNVERIFIED",
        "The destination account could not be verified");
  }

  public static OrganizerGoalException destinationUnavailable() {
    return new OrganizerGoalException(
        503,
        "Destination check unavailable",
        "DESTINATION_CHECK_UNAVAILABLE",
        "The destination could not be checked; try again later");
  }

  public static OrganizerGoalException notClosable() {
    return new OrganizerGoalException(
        409, "Goal not closable", "GOAL_NOT_CLOSABLE", "This goal cannot be closed");
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
