import { notFound } from "next/navigation";
import { PayoutApprovalForm } from "@/components/payout-approval-form";
import { getPayoutApproval } from "@/lib/api";

export const dynamic = "force-dynamic";

export default async function ApprovePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const approval = await getPayoutApproval(token);
  if (!approval) {
    notFound();
  }
  return <PayoutApprovalForm token={token} approval={approval} />;
}
