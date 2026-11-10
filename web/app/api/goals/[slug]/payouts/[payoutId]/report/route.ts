import { clientIp } from "@/lib/client-ip";
import { proxy } from "@/lib/proxy";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ slug: string; payoutId: string }> },
) {
  const { slug, payoutId } = await params;
  const headers: Record<string, string> = { "content-type": "application/json" };
  const ip = clientIp(request);
  if (ip) {
    headers["x-forwarded-for"] = ip;
  }
  return proxy(`/public/goals/${encodeURIComponent(slug)}/payouts/${encodeURIComponent(payoutId)}/report`, {
    method: "POST",
    headers,
    body: await request.text(),
  });
}
