import { backendHeaders, proxy } from "@/lib/proxy";

export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  return proxy(`/organizer/goals/${encodeURIComponent(id)}/payouts`, {
    headers: backendHeaders(request),
  });
}

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const headers = backendHeaders(request, { json: true });
  const key = request.headers.get("idempotency-key");
  if (key) {
    headers["idempotency-key"] = key;
  }
  return proxy(`/organizer/goals/${encodeURIComponent(id)}/payouts`, {
    method: "POST",
    headers,
    body: await request.text(),
  });
}
