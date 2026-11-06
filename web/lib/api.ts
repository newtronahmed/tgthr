import "server-only";

import { cache } from "react";
import type { ContributionStatus, PublicGoal } from "./types";

const API_BASE = (process.env.API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/+$/, "");

export async function getGoal(slug: string): Promise<PublicGoal | null> {
  const response = await fetch(`${API_BASE}/public/goals/${encodeURIComponent(slug)}`, {
    cache: "no-store",
    signal: AbortSignal.timeout(8_000),
  });
  if (response.status === 400 || response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`goal request failed with ${response.status}`);
  }
  return (await response.json()) as PublicGoal;
}

export const getGoalCached = cache(getGoal);

export async function getContribution(id: string): Promise<ContributionStatus | null> {
  const response = await fetch(
    `${API_BASE}/public/contributions/${encodeURIComponent(id)}`,
    { cache: "no-store", signal: AbortSignal.timeout(8_000) },
  );
  if (response.status === 400 || response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`contribution request failed with ${response.status}`);
  }
  return (await response.json()) as ContributionStatus;
}
