import type { Metadata } from "next";
import { OperatorEnroll } from "@/components/operator-enroll";

export const metadata: Metadata = {
  title: "Operator enrollment",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default async function EnrollmentTokenPage({
  params,
}: {
  params: Promise<{ token: string }>;
}) {
  const { token } = await params;
  return <OperatorEnroll token={token} />;
}
