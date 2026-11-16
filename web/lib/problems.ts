import type { Problem } from "./types";

export type AmountRules = {
  minimumAmountPesewas: number;
  fixedAmountPesewas?: number | null;
};

export function operatorProblemMessage(problem: Problem): string {
  switch (problem.code) {
    case "INVALID_CREDENTIALS":
      return "That username or password is not correct.";
    case "INVALID_CODE":
      return "That authenticator code is invalid or was already used.";
    case "MFA_CHALLENGE_EXPIRED":
      return "The sign-in challenge expired. Start again.";
    case "STEP_UP_REQUIRED":
      return "Enter a current authenticator code to continue.";
    case "FORBIDDEN":
      return "Your operator role does not allow this action.";
    case "REFUNDS_DISABLED":
      return "New refunds are disabled. Existing refunds can still be verified.";
    case "REFUND_IN_PROGRESS":
      return "Another refund is already active for this contribution.";
    case "REFUND_NOT_PENDING":
      return "This refund is no longer waiting for verification.";
    case "SUSPENSE_REQUIRES_RECONCILIATION":
      return "This is a suspense item. Resolve it from the reconciliation queue.";
    case "RECONCILIATION_ALREADY_RESOLVED":
      return "Another operator already resolved this item.";
    case "DUAL_CONTROL_REQUIRED":
      return "A different finance approver must approve this income.";
    case "SUSPENSE_NOT_AGED":
      return "This item is too recent to record as income.";
    case "REFUND_NOT_ELIGIBLE":
      return "This contribution is not eligible for a refund.";
    case "PAYOUT_IN_FLIGHT":
      return "A payout is in flight for this goal. Wait for it to finish.";
    case "REFUND_AFTER_PAYOUT":
      return "The goal has already paid out. This needs the P2 recovery workflow.";
    case "CONTRIBUTION_NOT_FOUND":
      return "No contribution matches that reference.";
    default:
      return problem.detail ?? "Something went wrong. Try again.";
  }
}

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
