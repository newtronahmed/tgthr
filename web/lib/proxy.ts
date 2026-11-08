import "server-only";

const API_BASE = (process.env.API_BASE_URL ?? "http://localhost:8080/v1").replace(/\/+$/, "");

export function backendHeaders(
  request: Request,
  options: { json?: boolean } = {},
): Record<string, string> {
  const headers: Record<string, string> = {};
  if (options.json) {
    headers["content-type"] = "application/json";
  }
  const cookie = request.headers.get("cookie");
  if (cookie) {
    headers.cookie = cookie;
  }
  const origin = request.headers.get("origin");
  if (origin) {
    headers.origin = origin;
  }
  const platformIp = request.headers.get("x-real-ip");
  if (platformIp) {
    headers["x-forwarded-for"] = platformIp;
  }
  return headers;
}

export async function readBody(request: Request, maxBytes = 32_768): Promise<string | null> {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(length) && length > maxBytes) {
    return null;
  }
  const body = await request.text();
  return body.length > maxBytes ? null : body;
}

export async function proxy(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    cache: "no-store",
    signal: AbortSignal.timeout(10_000),
  });
  const body = await response.text();
  const headers = new Headers({
    "content-type": response.headers.get("content-type") ?? "application/json",
    "cache-control": "no-store",
  });
  const correlationId = response.headers.get("x-correlation-id");
  if (correlationId) {
    headers.set("x-correlation-id", correlationId);
  }
  const retryAfter = response.headers.get("retry-after");
  if (retryAfter) {
    headers.set("retry-after", retryAfter);
  }
  const getSetCookie = (
    response.headers as Headers & { getSetCookie?: () => string[] }
  ).getSetCookie;
  const rawSetCookie = response.headers.get("set-cookie");
  const setCookies = getSetCookie
    ? getSetCookie.call(response.headers)
    : rawSetCookie
      ? [rawSetCookie]
      : [];
  for (const cookie of setCookies) {
    headers.append("set-cookie", cookie);
  }
  const noBody = response.status === 204 || response.status === 205 || response.status === 304;
  return new Response(noBody ? null : body, { status: response.status, headers });
}
