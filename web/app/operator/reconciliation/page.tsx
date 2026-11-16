import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { OperatorNav } from "@/components/operator-nav";
import {
  approveIncome,
  proposeIncome,
  reclassify,
  refundSuspense,
} from "@/app/operator/actions";
import {
  getOperatorProfile,
  getReconciliationQueue,
  type ReconciliationItem,
} from "@/lib/operator";
import { formatDateTime, formatGhs } from "@/lib/money";
import { operatorProblemMessage } from "@/lib/problems";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Reconciliation",
  robots: { index: false, follow: false },
};

const OK_MESSAGE: Record<string, string> = {
  reclassified: "Reclassification posted. The goal pool now owns the amount.",
  "suspense-refund-requested": "Suspense refund requested. Verify it when the provider is pending.",
  "income-proposed": "Income proposed. A different finance approver must apply it.",
  "income-approved": "Income approved and posted to PLATFORM_REVENUE.",
};

const inputClass =
  "w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15";

function formatAge(seconds: number): string {
  if (seconds < 60) {
    return `${Math.max(0, Math.floor(seconds))}s`;
  }
  if (seconds < 3_600) {
    return `${Math.floor(seconds / 60)}m`;
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3_600)}h ${Math.floor((seconds % 3_600) / 60)}m`;
  }
  return `${Math.floor(seconds / 86_400)}d ${Math.floor((seconds % 86_400) / 3_600)}h`;
}

export default async function ReconciliationPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; ok?: string }>;
}) {
  const profile = await getOperatorProfile();
  if (!profile) {
    redirect("/operator/signin");
  }
  const queue = await getReconciliationQueue();
  if (queue.kind === "unauthorized") {
    redirect("/operator/signin");
  }
  const params = await searchParams;
  const queueForbidden = queue.kind === "forbidden";
  const canReconcile = profile.roles.includes("RECONCILER");
  const canApprove = profile.roles.includes("FINANCE_APPROVER");
  const items = queue.kind === "ok" ? queue.data : [];
  const total = items.reduce((sum, item) => sum + item.amountMinorUnits, 0);
  const oldest = items.reduce((max, item) => Math.max(max, item.ageSeconds), 0);

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-2xl flex-col px-5 py-6">
      <OperatorNav profile={profile} />

      <section className="mt-8">
        <span className="text-[10px] font-black uppercase tracking-[0.16em] text-forest-700">
          Operator console
        </span>
        <h1 className="mt-1 text-3xl font-black leading-tight text-slate-900">Reconciliation</h1>
        <p className="mt-1 text-xs leading-relaxed text-slate-500">
          Late payments to closed goals sit in suspense. Refund, reclassify to the goal, or record
          income after the timeout. One resolution per item.
        </p>
      </section>

      <Banner error={params.error} ok={params.ok} />

      {queueForbidden && !canApprove ? (
        <div className="mt-10 rounded-3xl border border-dashed border-sage-300 bg-white/70 p-6 text-center">
          <p className="text-sm font-bold text-slate-900">Not available for your role</p>
          <p className="mt-1 text-xs text-slate-500">
            The reconciliation queue requires `RECONCILER` or `REFUND_OPERATOR`.
          </p>
        </div>
      ) : (
        <section className="mt-6 space-y-5">
          <div className="grid grid-cols-2 gap-3">
            <div className="rounded-3xl bg-forest-800 p-4 text-white shadow-soft-card">
              <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-sage-200">
                Queue value
              </span>
              <div className="mt-2 font-mono text-xl font-black">{formatGhs(total)}</div>
            </div>
            <div className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft-card">
              <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                Open items
              </span>
              <div className="mt-2 flex items-baseline gap-2">
                <span className="font-mono text-xl font-black text-slate-900">{items.length}</span>
                <span className="text-[10px] font-bold text-slate-400">
                  oldest {formatAge(oldest)}
                </span>
              </div>
            </div>
          </div>

          {queueForbidden ? (
            <div className="rounded-3xl border border-dashed border-sage-300 bg-white/70 p-5 text-center">
              <p className="text-sm font-bold text-slate-900">Queue view needs a reconciler role</p>
              <p className="mt-1 text-xs text-slate-500">
                You can still approve a proposed income below with the contribution reference.
              </p>
            </div>
          ) : null}

          {canApprove ? <ApproveIncomeForm /> : null}

          {!queueForbidden ? (
            <div className="space-y-3">
              {items.length === 0 ? (
                <div className="rounded-3xl border border-dashed border-sage-300 bg-white/70 p-6 text-center">
                  <p className="text-sm font-bold text-slate-900">Queue is clear</p>
                  <p className="mt-1 text-xs text-slate-500">
                    Late payments to closed goals land here for a refund, reclassification or
                    income.
                  </p>
                </div>
              ) : (
                items.map((item) => (
                  <ReconciliationCard
                    key={item.contributionId}
                    item={item}
                    canReconcile={canReconcile}
                  />
                ))
              )}
            </div>
          ) : null}
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

function ReconciliationCard({
  item,
  canReconcile,
}: {
  item: ReconciliationItem;
  canReconcile: boolean;
}) {
  return (
    <article className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft-card">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="font-mono text-lg font-black text-slate-900">
            {formatGhs(item.amountMinorUnits)}
          </div>
          <h2 className="mt-0.5 text-sm font-extrabold leading-snug text-slate-900">
            {item.goalTitle}
          </h2>
          <div className="mt-0.5 text-[10px] font-medium text-slate-500">
            Suspended {formatDateTime(item.suspendedAt)} · age {formatAge(item.ageSeconds)}
          </div>
        </div>
        <span className="rounded-full bg-amber-50 px-2.5 py-0.5 text-[10px] font-bold text-amber-700">
          SUSPENSE
        </span>
      </div>

      <dl className="mt-3 grid gap-2 text-[11px] sm:grid-cols-2">
        <div>
          <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Contribution</dt>
          <dd className="break-all font-mono text-slate-700">{item.contributionId}</dd>
        </div>
        <div>
          <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Goal</dt>
          <dd className="break-all font-mono text-slate-700">{item.goalId}</dd>
        </div>
        <div>
          <dt className="font-bold uppercase tracking-[0.1em] text-slate-400">Provider ref</dt>
          <dd className="break-all font-mono text-slate-700">{item.providerReference}</dd>
        </div>
      </dl>

      {canReconcile ? (
        <div className="mt-3 space-y-2 border-t border-slate-100 pt-3">
          <ResolutionDetails
            title="Reclassify to goal"
            copy="Debit suspense, credit the goal pool, and clear the reconciliation flag."
            action={reclassify}
            contributionId={item.contributionId}
            button="Reclassify"
          />
          <ResolutionDetails
            title="Refund payer"
            copy="Full A + P + F refund to the original payer; debits suspense, not the goal pool."
            action={refundSuspense}
            contributionId={item.contributionId}
            button="Request suspense refund"
          />
          <ResolutionDetails
            title="Propose income"
            copy="Park the amount as a proposed miscellaneous income for a finance approver."
            action={proposeIncome}
            contributionId={item.contributionId}
            button="Propose income"
          />
        </div>
      ) : null}
    </article>
  );
}

function ResolutionDetails({
  title,
  copy,
  action,
  contributionId,
  button,
}: {
  title: string;
  copy: string;
  action: (formData: FormData) => Promise<void>;
  contributionId: string;
  button: string;
}) {
  return (
    <details className="group rounded-2xl border border-slate-100 bg-sage-50/40 px-3 py-2">
      <summary className="cursor-pointer list-none text-[11px] font-extrabold text-slate-700 transition group-open:text-forest-700">
        {title}
      </summary>
      <form action={action} className="mt-2 space-y-2">
        <input type="hidden" name="contributionId" value={contributionId} />
        <input type="hidden" name="idempotencyKey" value={crypto.randomUUID()} />
        <p className="text-[11px] leading-relaxed text-slate-500">{copy}</p>
        <input
          type="text"
          name="reason"
          maxLength={500}
          className={inputClass}
          placeholder="Why this resolution is correct"
          required
        />
        <button
          type="submit"
          className="inline-flex w-full items-center justify-center rounded-xl bg-forest-600 px-3 py-2 text-[11px] font-extrabold text-white transition hover:bg-forest-700"
        >
          {button}
        </button>
      </form>
    </details>
  );
}

function ApproveIncomeForm() {
  return (
    <form
      action={approveIncome}
      className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card"
    >
      <input type="hidden" name="idempotencyKey" value={crypto.randomUUID()} />
      <h2 className="text-base font-extrabold text-slate-900">Approve proposed income</h2>
      <p className="mt-1 text-xs leading-relaxed text-slate-500">
        A different `FINANCE_APPROVER` must approve the reconciler&apos;s proposal. The amount moves
        from suspense to platform revenue.
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
          <input type="text" name="reason" maxLength={500} className={inputClass} required />
        </label>
        <label className="block">
          <span className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
            Authenticator code (if prompted)
          </span>
          <input
            type="text"
            name="totp"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            maxLength={6}
            className={`${inputClass} font-mono tracking-[0.3em]`}
            placeholder="123456"
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
            Recovery code (if TOTP is unavailable)
          </span>
          <input
            type="text"
            name="recoveryCode"
            autoComplete="off"
            spellCheck={false}
            className={`${inputClass} font-mono text-xs`}
          />
        </label>
      </div>
      <button
        type="submit"
        className="mt-4 inline-flex w-full items-center justify-center rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
      >
        Approve income
      </button>
    </form>
  );
}
