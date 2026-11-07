import { clientIp } from "@/lib/client-ip";
import { proxy } from "@/lib/proxy";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ slug: string }> },
) {
  const { slug } = await params;
  const headers: Record<string, string> = { "content-type": "application/json" };
  const idempotencyKey = request.headers.get("idempotency-key");
  if (idempotencyKey) {
    headers["idempotency-key"] = idempotencyKey;
  }
  const ip = clientIp(request);
  if (ip) {
    headers["x-forwarded-for"] = ip;
  }
  return proxy(`/public/goals/${encodeURIComponent(slug)}/contributions`, {
    method: "POST",
    headers,
    body: await request.text(),
  });
}
