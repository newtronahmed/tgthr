import { backendHeaders, proxy, readBody } from "@/lib/proxy";

export const dynamic = "force-dynamic";

const FORWARDED = ["idempotency-key", "x-operator-totp", "x-operator-recovery-code"];

export async function GET(
  request: Request,
  { params }: { params: Promise<{ path: string[] }> },
) {
  return forward(request, await params, "GET");
}

export async function POST(
  request: Request,
  { params }: { params: Promise<{ path: string[] }> },
) {
  return forward(request, await params, "POST");
}

async function forward(
  request: Request,
  params: { path: string[] },
  method: "GET" | "POST",
): Promise<Response> {
  const suffix = params.path.map((segment) => encodeURIComponent(segment)).join("/");
  const headers = backendHeaders(request, { json: method === "POST" });
  for (const name of FORWARDED) {
    const value = request.headers.get(name);
    if (value) {
      headers[name] = value;
    }
  }
  let body: string | undefined;
  if (method === "POST") {
    const raw = await readBody(request);
    if (raw === null) {
      return new Response(null, { status: 413 });
    }
    body = raw;
  }
  return proxy(`/operator/${suffix}`, { method, headers, body }).then(rewriteOperatorCookies);
}

async function rewriteOperatorCookies(response: Response): Promise<Response> {
  const getSetCookie = (response.headers as Headers & { getSetCookie?: () => string[] })
    .getSetCookie;
  const raw = response.headers.get("set-cookie");
  const cookies = getSetCookie ? getSetCookie.call(response.headers) : raw ? [raw] : [];
  if (cookies.length === 0) {
    return response;
  }
  const headers = new Headers(response.headers);
  headers.delete("set-cookie");
  for (const cookie of cookies) {
    headers.append("set-cookie", cookie.replace(/;\s*Path=[^;]*/i, "; Path=/operator"));
  }
  const body = await response.text();
  return new Response(response.status === 204 ? null : body, {
    status: response.status,
    headers,
  });
}
