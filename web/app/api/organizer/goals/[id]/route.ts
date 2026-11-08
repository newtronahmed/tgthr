import { backendHeaders, proxy } from "@/lib/proxy";

export async function GET(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  return proxy(`/organizer/goals/${encodeURIComponent(id)}`, {
    headers: backendHeaders(request),
  });
}
