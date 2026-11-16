import { enrollmentProxy } from "@/lib/operator-enrollment-proxy";

export async function POST(request: Request) {
  const response = await enrollmentProxy(request, "/operator/auth/enroll/confirm");
  if (response.status !== 204) {
    return response;
  }
  const headers = new Headers(response.headers);
  headers.append(
    "set-cookie",
    "tgthr_operator_enrollment=; Path=/operator; Max-Age=0; HttpOnly; SameSite=Strict",
  );
  return new Response(null, { status: 204, headers });
}
