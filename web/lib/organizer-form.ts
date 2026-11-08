import { parseGhsToPesewas } from "./money.ts";
import type { CreateGoalInput, Network } from "./types";

export type GoalFormState = {
  template: CreateGoalInput["template"];
  title: string;
  targetType: "FIXED" | "OPEN";
  fixedAmount: string;
  minimumAmount: string;
  targetAmount: string;
  deadline: string;
  destinationType: "BENEFICIARY_MOMO" | "ADMIN_MOMO";
  destinationPhone: string;
  network: Network;
  secondApprover: "OFF" | "ABOVE_THRESHOLD" | "ALWAYS";
  threshold: string;
  holdWindow: string;
  surplusRule: "PAY_BENEFICIARY" | "WELFARE_RESERVE";
};

export type GoalPayloadResult =
  | { ok: true; payload: CreateGoalInput }
  | { ok: false; error: string };

function localPhone(input: string): string | null {
  const value = input.replace(/[\s-]/g, "");
  const local = value.startsWith("+233") ? `0${value.slice(4)}` : value;
  return /^0[0-9]{9}$/.test(local) ? local : null;
}

export function buildGoalPayload(
  form: GoalFormState,
  now: Date = new Date(),
): GoalPayloadResult {
  const title = form.title.trim();
  if (title.length < 3 || title.length > 120) {
    return { ok: false, error: "Give the goal a title between 3 and 120 characters." };
  }
  const phone = localPhone(form.destinationPhone);
  if (!phone) {
    return { ok: false, error: "Enter the payout number as 0XXXXXXXXX or +233XXXXXXXXX." };
  }
  if (!form.deadline) {
    return { ok: false, error: "Choose a deadline." };
  }
  const deadline = new Date(`${form.deadline}T23:59:59`);
  if (Number.isNaN(deadline.getTime()) || deadline.getTime() <= now.getTime()) {
    return { ok: false, error: "The deadline must be in the future." };
  }
  const target = form.targetAmount ? parseGhsToPesewas(form.targetAmount) : undefined;
  if (target === null) {
    return { ok: false, error: "Enter a valid target amount." };
  }
  const threshold =
    form.threshold.trim() === "0"
      ? 0
      : form.threshold
        ? parseGhsToPesewas(form.threshold)
        : 50_000;
  if (threshold === null) {
    return { ok: false, error: "Enter a valid second-approver threshold." };
  }
  const holdWindow = Number(form.holdWindow);
  if (!Number.isInteger(holdWindow) || holdWindow < 0 || holdWindow > 1_440) {
    return { ok: false, error: "The hold window must be between 0 and 1,440 minutes." };
  }

  const base = {
    title,
    template: form.template,
    targetType: form.targetType,
    deadline: deadline.toISOString(),
    destinationType: form.destinationType,
    destinationPhone: phone,
    network: form.network,
    secondApprover: form.secondApprover,
    secondApproverThresholdPesewas: threshold,
    holdWindowMinutes: holdWindow,
    surplusRule: form.surplusRule,
  };

  if (form.targetType === "FIXED") {
    const fixed = parseGhsToPesewas(form.fixedAmount);
    if (fixed === null) {
      return { ok: false, error: "Enter the fixed contribution amount." };
    }
    return {
      ok: true,
      payload: { ...base, fixedAmountPesewas: fixed, targetAmountPesewas: target },
    };
  }

  const minimum = parseGhsToPesewas(form.minimumAmount);
  if (minimum === null || minimum < 1_000) {
    return { ok: false, error: "Open goals need a minimum of GHS 10.00." };
  }
  return {
    ok: true,
    payload: { ...base, minimumAmountPesewas: minimum, targetAmountPesewas: target },
  };
}
