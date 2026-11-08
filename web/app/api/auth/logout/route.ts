import { backendHeaders, proxy } from "@/lib/proxy";

export async function POST(request: Request) {
  return proxy("/auth/logout", {
    method: "POST",
    headers: backendHeaders(request),
  });
}
