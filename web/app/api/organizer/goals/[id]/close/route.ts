import { backendHeaders, proxy } from "@/lib/proxy";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  return proxy(`/organizer/goals/${encodeURIComponent(id)}/close`, {
    method: "POST",
    headers: backendHeaders(request),
  });
}
