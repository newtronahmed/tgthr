import { backendHeaders, proxy } from "@/lib/proxy";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ token: string }> },
) {
  const { token } = await params;
  return proxy(`/payout-approvals/${encodeURIComponent(token)}/verify`, {
    method: "POST",
    headers: backendHeaders(request, { json: true }),
    body: await request.text(),
  });
}
