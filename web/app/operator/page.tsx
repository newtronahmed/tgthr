import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { OperatorNav } from "@/components/operator-nav";
import { requestRefund, verifyRefund } from "@/app/operator/actions";
import { getOperatorProfile, getRefunds, type RefundState, type RefundView } from "@/lib/operator";
import { formatDateTime, formatGhs } from "@/lib/money";
import { operatorProblemMessage } from "@/lib/problems";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Refunds",
  robots: { index: false, follow: false },
};

const STATE_STYLE: Record<RefundState, string> = {
  PENDING: "bg-slate-100 text-slate-700",
  PROVIDER_PENDING: "bg-amber-50 text-amber-700",
  SUCCEEDED: "bg-sage-100 text-forest-700",
  FAILED: "bg-red-50 text-red-700",
};

const OK_MESSAGE: Record<string, string> = {
  "refund-requested": "Refund requested. The provider call runs outside the request transaction.",
  "refund-verified": "Provider result applied. Refresh is automatic; check the state below.",
};

const inputClass =
  "w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15";

export default async function OperatorRefundsPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; ok?: string }>;
}) {
  const profile = await getOperatorProfile();
  if (!profile) {
    redirect("/operator/signin");
  }
  const refunds = await getRefunds();
  if (refunds.kind === "unauthorized") {
    redirect("/operator/signin");
  }
  const params = await searchParams;
  const canRefund = profile.roles.includes("REFUND_OPERATOR");
  const pending = refunds.kind === "ok" ? refunds.data : [];

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-2xl flex-col px-5 py-6">
      <OperatorNav profile={profile} />

      <section className="mt-8">
        <span className="text-[10px] font-black uppercase tracking-[0.16em] text-forest-700">
          Operator console
        </span>
        <h1 className="mt-1 text-3xl font-black leading-tight text-slate-900">Refunds</h1>
        <p className="mt-1 text-xs leading-relaxed text-slate-500">
          Request a full pre-payout refund, or verify a provider-pending refund. Suspense items are
          resolved from the reconciliation queue.
        </p>
      </section>

      <Banner error={params.error} ok={params.ok} />

      {refunds.kind === "forbidden" ? (
        <div className="mt-6 rounded-3xl border border-dashed border-sage-300 bg-white/70 p-6 text-center">
          <p className="text-sm font-bold text-slate-900">Not available for your role</p>
          <p className="mt-1 text-xs text-slate-500">
            Refunds require `REFUND_OPERATOR`. Use the reconciliation queue if you are a
            `RECONCILER`.
          </p>
        </div>
      ) : (
        <section className="mt-6 space-y-5">
          <div className="grid grid-cols-2 gap-3">
            <div className="rounded-3xl bg-forest-800 p-4 text-white shadow-soft-card">
              <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-sage-200">
                Recent refunds
              </span>
              <div className="mt-2 font-mono text-xl font-black">{pending.length}</div>
            </div>
            <div className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft-card">
              <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                Waiting on provider
              </span>
              <div className="mt-2 font-mono text-xl font-black text-slate-900">
                {
                  pending.filter(
                    (refund) =>
                      refund.state === "PENDING" || refund.state === "PROVIDER_PENDING",
                  ).length
                }
              </div>
            </div>
          </div>

          {canRefund ? (
            <form
              action={requestRefund}
              className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card"
            >
              <input type="hidden" name="idempotencyKey" value={crypto.randomUUID()} />
              <h2 className="text-base font-extrabold text-slate-900">Request a refund</h2>
              <p className="mt-1 text-xs leading-relaxed text-slate-500">
                Full pre-payout refund to the original payer. The contribution must be confirmed and
                the goal must not have an active or completed payout.
              </p>
              <div className="mt-4 space-y-3">
                <label className="block">
                  <span className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                    Contribution ID
                  </span>
                  <input
                    type="text"
                    name="contributionId"
                    className={`${inputClass} font-mono text-xs`}
                    placeholder="00000000-0000-0000-0000-000000000000"
                    required
                  />
                </label>
                <label className="block">
                  <span className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                    Reason
                  </span>
                  <input
                    type="text"
                    name="reason"
                    maxLength={500}
                    className={inputClass}
                    placeholder="Duplicate charge"
                    required
                  />
                </label>
              </div>
              <button
                type="submit"
                className="mt-4 inline-flex w-full items-center justify-center rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
              >
                Request refund
              </button>
            </form>
          ) : (
            <div className="rounded-3xl border border-dashed border-sage-300 bg-white/70 p-5 text-center">
              <p className="text-sm font-bold text-slate-900">Read-only refund view</p>
              <p className="mt-1 text-xs text-slate-500">
                A `REFUND_OPERATOR` role is required to request or verify a refund.
              </p>
            </div>
          )}

          <div className="space-y-3">
            {pending.length === 0 ? (
              <div className="rounded-3xl border border-dashed border-sage-300 bg-white/70 p-6 text-center">
                <p className="text-sm font-bold text-slate-900">No refunds yet</p>
                <p className="mt-1 text-xs text-slate-500">
                  Request one above, or wait for an operator action to appear here.
                </p>
              </div>
            ) : (
              pending.map((refund) => (
                <RefundCard key={refund.id} refund={refund} canVerify={canRefund} />
              ))
            )}
          </div>
        </section>
      )}
    </main>
  );
}

function Banner({ error, ok }: { error?: string; ok?: string }) {
  if (error) {
    return (
      <div className="mt-5 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-xs font-semibold text-red-700">
        {error === "UNAUTHORIZED"
          ? "Your session ended. Sign in again."
          : operatorProblemMessage({ code: error })}
      </div>
    );
  }
  if (ok && OK_MESSAGE[ok]) {
    return (
      <div className="mt-5 rounded-2xl border border-sage-300 bg-sage-50 px-4 py-3 text-xs font-semibold text-forest-700">
        {OK_MESSAGE[ok]}
      </div>
    );
  }
  return null;
}

function RefundCard({ refund, canVerify }: { refund: RefundView; canVerify: boolean }) {
  const verifiable =
    canVerify && (refund.state === "PENDING" || refund.state === "PROVIDER_PENDING");
  return (
    <article className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft-card">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="font-mono text-lg font-black text-slate-900">
            {formatGhs(refund.totalMinorUnits)}
          </div>
          <div className="mt-0.5 text-[10px] font-medium text-slate-500">
            {refund.sourceAccount === "SUSPENSE" ? "Suspense source" : "Goal pool source"} ·{" "}
            {formatDateTime(refund.createdAt)}
          </div>
        </div>
        <span
          className={`rounded-full px-2.5 py-0.5 text-[10px] font-bold ${STATE_STYLE[refund.state]}`}
        >
          {refund.state}
        </span>
      </div>

      <dl className="mt-3 grid gap-2 text-[11px] sm:grid-cols-2">
        <div>
          <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Contribution</dt>
          <dd className="break-all font-mono text-slate-700">{refund.contributionId}</dd>
        </div>
        <div>
          <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Reason</dt>
          <dd className="text-slate-700">{refund.reason}</dd>
        </div>
        {refund.providerReference ? (
          <div>
            <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Provider ref</dt>
            <dd className="break-all font-mono text-slate-700">{refund.providerReference}</dd>
          </div>
        ) : null}
        {refund.failureCode ? (
          <div>
            <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Failure</dt>
            <dd className="font-mono text-red-600">{refund.failureCode}</dd>
          </div>
        ) : null}
      </dl>

      {verifiable ? (
        <form action={verifyRefund} className="mt-3 space-y-2 border-t border-slate-100 pt-3">
          <input type="hidden" name="refundId" value={refund.id} />
          <input type="hidden" name="idempotencyKey" value={crypto.randomUUID()} />
          <label className="block">
            <span className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Verification reason
            </span>
            <input
              type="text"
              name="reason"
              defaultValue="provider verification"
              maxLength={500}
              className={inputClass}
              required
            />
          </label>
          <button
            type="submit"
            className="inline-flex items-center justify-center rounded-2xl border border-forest-600 px-4 py-2 text-xs font-extrabold text-forest-700 transition hover:bg-sage-50"
          >
            Verify with Paystack
          </button>
        </form>
      ) : null}
    </article>
  );
}
