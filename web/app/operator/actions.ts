"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { cookies } from "next/headers";

const API_BASE = (process.env.API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/+$/, "");
const COOKIE = "tgthr_operator_session";

type Result = { ok: true } | { ok: false; code: string };

async function post(
  path: string,
  body: Record<string, unknown>,
  idempotencyKey: string,
  totp?: string,
  recoveryCode?: string,
): Promise<Result> {
  const store = await cookies();
  const token = store.get(COOKIE)?.value;
  if (!token) {
    return { ok: false, code: "UNAUTHORIZED" };
  }
  const headers: Record<string, string> = {
    "content-type": "application/json",
    cookie: `${COOKIE}=${token}`,
  };
  if (idempotencyKey) {
    headers["Idempotency-Key"] = idempotencyKey;
  }
  if (totp) {
    headers["X-Operator-TOTP"] = totp;
  }
  if (recoveryCode) {
    headers["X-Operator-Recovery-Code"] = recoveryCode;
  }
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
    if (response.ok) {
      return { ok: true };
    }
    const problem = (await response.json().catch(() => ({}))) as { code?: string };
    return { ok: false, code: problem.code ?? "UNKNOWN" };
  } catch {
    return { ok: false, code: "UNAVAILABLE" };
  }
}

function finish(result: Result, path: string, ok: string): never {
  revalidatePath(path);
  if (!result.ok) {
    redirect(`${path}?error=${encodeURIComponent(result.code)}`);
  }
  redirect(`${path}?ok=${encodeURIComponent(ok)}`);
}

function value(formData: FormData, name: string): string {
  return String(formData.get(name) ?? "").trim();
}

export async function requestRefund(formData: FormData) {
  const contributionId = value(formData, "contributionId");
  const reason = value(formData, "reason");
  const key = value(formData, "idempotencyKey");
  if (!contributionId || !reason || !key) {
    redirect("/operator?error=INVALID_REQUEST");
  }
  const result = await post(
    `/operator/contributions/${encodeURIComponent(contributionId)}/refund`,
    { reason },
    key,
  );
  finish(result, "/operator", "refund-requested");
}

export async function verifyRefund(formData: FormData) {
  const refundId = value(formData, "refundId");
  const reason = value(formData, "reason");
  const key = value(formData, "idempotencyKey");
  if (!refundId || !reason || !key) {
    redirect("/operator?error=INVALID_REQUEST");
  }
  const result = await post(
    `/operator/refunds/${encodeURIComponent(refundId)}/verify`,
    { reason },
    key,
  );
  finish(result, "/operator", "refund-verified");
}

export async function reclassify(formData: FormData) {
  await resolve(formData, "reclassify", "reclassified");
}

export async function refundSuspense(formData: FormData) {
  await resolve(formData, "refund", "suspense-refund-requested");
}

export async function proposeIncome(formData: FormData) {
  await resolve(formData, "income", "income-proposed");
}

async function resolve(formData: FormData, action: string, ok: string) {
  const contributionId = value(formData, "contributionId");
  const reason = value(formData, "reason");
  const key = value(formData, "idempotencyKey");
  if (!contributionId || !reason || !key) {
    redirect("/operator/reconciliation?error=INVALID_REQUEST");
  }
  const result = await post(
    `/operator/reconciliation/${encodeURIComponent(contributionId)}/${action}`,
    { reason },
    key,
  );
  finish(result, "/operator/reconciliation", ok);
}

export async function approveIncome(formData: FormData) {
  const contributionId = value(formData, "contributionId");
  const reason = value(formData, "reason");
  const key = value(formData, "idempotencyKey");
  const totp = value(formData, "totp");
  const recoveryCode = value(formData, "recoveryCode");
  if (!contributionId || !reason || !key) {
    redirect("/operator/reconciliation?error=INVALID_REQUEST");
  }
  const result = await post(
    `/operator/reconciliation/${encodeURIComponent(contributionId)}/income/approve`,
    { reason },
    key,
    totp || undefined,
    recoveryCode || undefined,
  );
  finish(result, "/operator/reconciliation", "income-approved");
}
