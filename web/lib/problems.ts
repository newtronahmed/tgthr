import type { Problem } from "./types";

export type AmountRules = {
  minimumAmountPesewas: number;
  fixedAmountPesewas?: number | null;
};

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
