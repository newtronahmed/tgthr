import { backendHeaders, proxy } from "@/lib/proxy";

export async function GET(
  request: Request,
  { params }: { params: Promise<{ token: string }> },
) {
  const { token } = await params;
  return proxy(`/payout-approvals/${encodeURIComponent(token)}`, {
    headers: backendHeaders(request),
  });
}
