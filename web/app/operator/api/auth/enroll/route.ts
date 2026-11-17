import { enrollmentProxy } from "@/lib/operator-enrollment-proxy";

export async function POST(request: Request) {
  return enrollmentProxy(request, "/operator/auth/enroll");
}
