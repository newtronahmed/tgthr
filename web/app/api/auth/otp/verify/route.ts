import { backendHeaders, proxy, readBody } from "@/lib/proxy";

export async function POST(request: Request) {
  const body = await readBody(request);
  if (body === null) {
    return new Response(null, { status: 413 });
  }
  return proxy("/auth/otp/verify", {
    method: "POST",
    headers: backendHeaders(request, { json: true }),
    body,
  });
}
