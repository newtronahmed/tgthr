import { backendHeaders, proxy } from "@/lib/proxy";

export async function GET(request: Request) {
  return proxy("/organizer/me", { headers: backendHeaders(request) });
}
