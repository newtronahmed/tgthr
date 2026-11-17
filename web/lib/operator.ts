import "server-only";

import { cookies } from "next/headers";
import type { OperatorProfile, OperatorResult, ReconciliationItem, RefundView } from "./operator-types";

const API_BASE = (process.env.API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/+$/, "");
const COOKIE = "tgthr_operator_session";

export type {
  OperatorProfile,
  OperatorResult,
  OperatorRole,
  ReconciliationItem,
  RefundState,
  RefundView,
} from "./operator-types";

async function operatorGet(path: string): Promise<Response> {
  const store = await cookies();
  const token = store.get(COOKIE)?.value;
  if (!token) {
    return new Response(null, { status: 401 });
  }
  return fetch(`${API_BASE}${path}`, {
    headers: { cookie: `${COOKIE}=${token}` },
    cache: "no-store",
    signal: AbortSignal.timeout(8_000),
  });
}

export async function getOperatorProfile(): Promise<OperatorProfile | null> {
  const response = await operatorGet("/operator/me");
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`operator profile request failed with ${response.status}`);
  }
  return (await response.json()) as OperatorProfile;
}

export async function getRefunds(): Promise<OperatorResult<RefundView[]>> {
  const response = await operatorGet("/operator/refunds");
  if (response.status === 401) {
    return { kind: "unauthorized" };
  }
  if (response.status === 403) {
    return { kind: "forbidden" };
  }
  if (!response.ok) {
    throw new Error(`refund list request failed with ${response.status}`);
  }
  return { kind: "ok", data: (await response.json()) as RefundView[] };
}

export async function getReconciliationQueue(): Promise<
  OperatorResult<ReconciliationItem[]>
> {
  const response = await operatorGet("/operator/reconciliation/pending");
  if (response.status === 401) {
    return { kind: "unauthorized" };
  }
  if (response.status === 403) {
    return { kind: "forbidden" };
  }
  if (!response.ok) {
    throw new Error(`reconciliation queue request failed with ${response.status}`);
  }
  return { kind: "ok", data: (await response.json()) as ReconciliationItem[] };
}
