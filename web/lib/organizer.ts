import "server-only";

import { cookies } from "next/headers";
import type { OrganizerGoal, OrganizerProfile } from "./types";

const API_BASE = (process.env.API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/+$/, "");

async function organizerGet(path: string): Promise<Response> {
  const store = await cookies();
  const token = store.get("tgthr_session")?.value;
  if (!token) {
    return new Response(null, { status: 401 });
  }
  return fetch(`${API_BASE}${path}`, {
    headers: { cookie: `tgthr_session=${token}` },
    cache: "no-store",
    signal: AbortSignal.timeout(8_000),
  });
}

export async function getOrganizerProfile(): Promise<OrganizerProfile | null> {
  const response = await organizerGet("/organizer/me");
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`organizer profile request failed with ${response.status}`);
  }
  return (await response.json()) as OrganizerProfile;
}

export async function getOrganizerGoals(): Promise<OrganizerGoal[] | null> {
  const response = await organizerGet("/organizer/goals");
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new Error(`organizer goals request failed with ${response.status}`);
  }
  return (await response.json()) as OrganizerGoal[];
}

export type OrganizerGoalResult =
  | { kind: "ok"; goal: OrganizerGoal }
  | { kind: "unauthorized" }
  | { kind: "not_found" };

export async function getOrganizerGoal(id: string): Promise<OrganizerGoalResult> {
  const response = await organizerGet(`/organizer/goals/${encodeURIComponent(id)}`);
  if (response.status === 401) {
    return { kind: "unauthorized" };
  }
  if (response.status === 400 || response.status === 404) {
    return { kind: "not_found" };
  }
  if (!response.ok) {
    throw new Error(`organizer goal request failed with ${response.status}`);
  }
  return { kind: "ok", goal: (await response.json()) as OrganizerGoal };
}
