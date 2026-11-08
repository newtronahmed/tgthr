import type { Problem } from "./types";

export type AmountRules = {
  minimumAmountPesewas: number;
  fixedAmountPesewas?: number | null;
};

export function organizerProblemMessage(problem: Problem): string {
  switch (problem.code) {
    case "INVALID_OTP":
      return "That code is invalid or has expired. Request a new one.";
    case "RESEND_TOO_SOON":
      return "Wait before requesting another code.";
    case "RATE_LIMITED":
      return "Too many attempts. Wait a minute and try again.";
    case "LEGAL_NAME_REQUIRED":
      return "Add your legal name on the sign-in screen before using your own MoMo.";
    case "DESTINATION_UNVERIFIED":
      return "The payout account could not be verified. Check the number, network and legal name.";
    case "DESTINATION_CHECK_UNAVAILABLE":
      return "The payout check is unavailable right now. Try again shortly.";
    case "GOAL_NOT_CLOSABLE":
      return "This goal cannot be closed in its current state.";
    case "GOAL_NOT_FOUND":
      return "This goal could not be found.";
    case "UNAUTHENTICATED":
      return "Your session has ended. Sign in again.";
    case "INVALID_REQUEST":
      return "Check the highlighted fields and try again.";
    default:
      return problem.detail ?? "Something went wrong. Try again.";
  }
}

export function problemMessage(problem: Problem, rules: AmountRules): string {
  switch (problem.code) {
    case "AMOUNT_TOO_LOW":
      return `The minimum contribution is GHS ${(rules.minimumAmountPesewas / 100).toFixed(2)}.`;
    case "AMOUNT_NOT_ALLOWED":
      return rules.fixedAmountPesewas
        ? `This goal accepts exactly GHS ${(rules.fixedAmountPesewas / 100).toFixed(2)}.`
        : "That amount is not allowed for this goal.";
    case "GOAL_NOT_ACCEPTING":
      return "This goal is no longer accepting contributions.";
    case "RATE_LIMITED":
      return "Too many attempts. Wait a minute and try again.";
    case "INVALID_REQUEST":
      return "Check the amount and mobile money number.";
    case "INVALID_WEBHOOK":
      return "The payment result could not be read. Try again.";
    case "GOAL_NOT_FOUND":
      return "This goal could not be found.";
    default:
      return problem.detail ?? "Something went wrong. Try again.";
  }
}
