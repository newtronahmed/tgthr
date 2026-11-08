import { backendHeaders, proxy, readBody } from "@/lib/proxy";

export async function GET(request: Request) {
  return proxy("/organizer/goals", { headers: backendHeaders(request) });
}

export async function POST(request: Request) {
  const body = await readBody(request);
  if (body === null) {
    return new Response(null, { status: 413 });
  }
  const headers = backendHeaders(request, { json: true });
  const idempotencyKey = request.headers.get("idempotency-key");
  if (idempotencyKey) {
    headers["idempotency-key"] = idempotencyKey;
  }
  return proxy("/organizer/goals", {
    method: "POST",
    headers,
    body,
  });
}
