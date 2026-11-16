"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

const API_BASE = (process.env.API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/+$/, "");
const CHALLENGE = "tgthr_operator_challenge";
const USERNAME = "tgthr_operator_username";
const SESSION = "tgthr_operator_session";

function secureCookies(): boolean {
  if (process.env.COOKIE_SECURE === "false") {
    return false;
  }
  if (process.env.COOKIE_SECURE === "true") {
    return true;
  }
  return process.env.NODE_ENV === "production";
}

async function problemCode(response: Response): Promise<string> {
  const problem = (await response.json().catch(() => ({}))) as { code?: string };
  return problem.code ?? "UNKNOWN";
}

export async function login(formData: FormData) {
  const username = String(formData.get("username") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  if (!username || !password) {
    redirect("/operator/signin?error=INVALID_REQUEST");
  }
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/operator/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ username, password }),
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    redirect("/operator/signin?error=UNAVAILABLE");
  }
  if (!response.ok) {
    redirect(`/operator/signin?error=${encodeURIComponent(await problemCode(response))}`);
  }
  const body = (await response.json()) as { challengeToken: string };
  const jar = await cookies();
  const common = {
    httpOnly: true,
    sameSite: "strict" as const,
    secure: secureCookies(),
    path: "/operator",
    maxAge: 300,
  };
  jar.set(CHALLENGE, body.challengeToken, common);
  jar.set(USERNAME, username, common);
  redirect("/operator/signin?step=mfa");
}

export async function mfa(formData: FormData) {
  const code = String(formData.get("code") ?? "").replace(/\D/g, "");
  const jar = await cookies();
  const challenge = jar.get(CHALLENGE)?.value;
  if (!challenge || code.length !== 6) {
    redirect("/operator/signin?error=MFA_CHALLENGE_EXPIRED");
  }
  let response: Response;
  try {
    response = await fetch(`${API_BASE}/operator/auth/mfa`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ challengeToken: challenge, code }),
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
  } catch {
    redirect("/operator/signin?step=mfa&error=UNAVAILABLE");
  }
  if (!response.ok) {
    const failure = await problemCode(response);
    if (failure === "MFA_CHALLENGE_EXPIRED") {
      jar.delete({ name: CHALLENGE, path: "/operator" });
      jar.delete({ name: USERNAME, path: "/operator" });
      redirect("/operator/signin?error=MFA_CHALLENGE_EXPIRED");
    }
    redirect(`/operator/signin?step=mfa&error=${encodeURIComponent(failure)}`);
  }
  const setCookie = response.headers.get("set-cookie") ?? "";
  const token = /tgthr_operator_session=([^;]+)/.exec(setCookie)?.[1];
  if (!token) {
    redirect("/operator/signin?step=mfa&error=UNAVAILABLE");
  }
  const maxAge = Number(/Max-Age=(\d+)/i.exec(setCookie)?.[1] ?? 8 * 60 * 60);
  jar.set(SESSION, decodeURIComponent(token), {
    httpOnly: true,
    sameSite: "strict",
    secure: secureCookies(),
    path: "/operator",
    maxAge: Number.isFinite(maxAge) && maxAge > 0 ? maxAge : 8 * 60 * 60,
  });
  jar.delete({ name: CHALLENGE, path: "/operator" });
  jar.delete({ name: USERNAME, path: "/operator" });
  redirect("/operator");
}

export async function resetChallenge() {
  const jar = await cookies();
  jar.delete({ name: CHALLENGE, path: "/operator" });
  jar.delete({ name: USERNAME, path: "/operator" });
  redirect("/operator/signin");
}

export async function selectEnrollmentToken(formData: FormData) {
  const token = String(formData.get("token") ?? "").trim();
  if (!token) {
    redirect("/operator/enroll?error=INVALID_REQUEST");
  }
  redirect(`/operator/enroll/${encodeURIComponent(token)}`);
}

export async function logout() {
  const jar = await cookies();
  const token = jar.get(SESSION)?.value;
  if (token) {
    try {
      await fetch(`${API_BASE}/operator/auth/logout`, {
        method: "POST",
        headers: { cookie: `${SESSION}=${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(5_000),
      });
    } catch {
      // The local cookie is cleared even if the backend is unreachable.
    }
  }
  jar.delete({ name: SESSION, path: "/operator" });
  redirect("/operator/signin");
}
