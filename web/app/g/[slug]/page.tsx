import { BrandHeader } from "@/components/brand";
import { Checkout } from "@/components/checkout";
import { Shield, User, WhatsApp } from "@/components/icons";
import { formatDateTime, formatGhs } from "@/lib/money";
import { getGoalCached } from "@/lib/api";
import type { Metadata } from "next";
import { notFound } from "next/navigation";

export const dynamic = "force-dynamic";

const TEMPLATE_LABELS: Record<string, string> = {
  TRIP: "Trip",
  WEDDING: "Wedding",
  FUNERAL: "Funeral",
  RENT: "Rent",
  COMMUNITY: "Community",
  GIFT: "Gift",
  PARTY: "Party",
};

function isPast(value: string | null | undefined): boolean {
  if (!value) {
    return false;
  }
  return new Date(value).getTime() <= Date.now();
}

const TEMPLATE_STYLES: Record<string, string> = {
  TRIP: "bg-blue-100 text-blue-700",
  WEDDING: "bg-pink-100 text-pink-700",
  FUNERAL: "bg-slate-200 text-slate-700",
  RENT: "bg-amber-100 text-amber-700",
  COMMUNITY: "bg-forest-100 text-forest-700",
  GIFT: "bg-purple-100 text-purple-700",
  PARTY: "bg-orange-100 text-orange-700",
};

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const goal = await getGoalCached(slug).catch(() => null);
  if (!goal) {
    return { title: "Goal not found" };
  }
  const description = `${formatGhs(goal.raisedPesewas)} raised from ${goal.contributorCount} contributions. Contribute by mobile money on tgthr.`;
  return {
    title: goal.title,
    description,
    openGraph: { title: goal.title, description, type: "website" },
  };
}

export default async function GoalPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const goal = await getGoalCached(slug);
  if (!goal) {
    notFound();
  }

  const target = goal.targetAmountPesewas ?? null;
  const progress =
    target && target > 0 ? Math.min(100, Math.round((goal.raisedPesewas / target) * 100)) : null;
  const deadlinePassed = isPast(goal.deadline);
  const accepting = goal.status === "OPEN" && !deadlinePassed;

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
      <BrandHeader />

      <div className="mt-6 inline-flex w-fit items-center gap-2 rounded-2xl border border-slate-200 bg-white px-3 py-2 text-[11px] shadow-sm">
        <WhatsApp className="h-4 w-4 text-emerald-500" />
        <span className="text-slate-500">
          Shared with you on <span className="font-semibold text-slate-800">tgthr</span>
        </span>
      </div>

      <section className="mt-4 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <span
          className={`inline-flex rounded-full px-2.5 py-0.5 text-[10px] font-bold ${
            TEMPLATE_STYLES[goal.template] ?? "bg-sage-100 text-forest-700"
          }`}
        >
          {TEMPLATE_LABELS[goal.template] ?? goal.template} goal
        </span>
        <h1 className="mt-2 text-2xl font-black leading-tight text-slate-900">{goal.title}</h1>
        <p className="mt-1 text-xs text-slate-500">
          Organized by the group on tgthr
          {goal.deadline ? ` · due ${formatDateTime(goal.deadline)}` : ""}
        </p>

        <div className="mt-4 space-y-2">
          <div className="flex items-end justify-between gap-3">
            <div>
              <div className="font-mono text-2xl font-black text-slate-900">
                {formatGhs(goal.raisedPesewas)}
              </div>
              <div className="text-[11px] font-medium text-slate-500">
                raised{target ? ` of ${formatGhs(target)}` : ""}
              </div>
            </div>
            <div className="rounded-full bg-sage-100 px-3 py-1 text-[10px] font-bold text-forest-700">
              {goal.contributorCount} contribution{goal.contributorCount === 1 ? "" : "s"}
            </div>
          </div>
          {progress !== null ? (
            <div className="h-2 overflow-hidden rounded-full bg-sage-100">
              <div
                className="h-full rounded-full bg-forest-600 transition-all"
                style={{ width: `${progress}%` }}
              />
            </div>
          ) : null}
          {goal.targetType === "FIXED" && goal.fixedAmountPesewas ? (
            <p className="text-[11px] font-medium text-slate-500">
              This goal accepts exactly{" "}
              <span className="font-bold text-slate-800">
                {formatGhs(goal.fixedAmountPesewas)}
              </span>{" "}
              per contribution.
            </p>
          ) : (
            <p className="text-[11px] font-medium text-slate-500">
              Minimum contribution{" "}
              <span className="font-bold text-slate-800">
                {formatGhs(goal.minimumAmountPesewas)}
              </span>
              .
            </p>
          )}
        </div>
      </section>

      {!accepting ? (
        <section className="mt-4 rounded-3xl border border-amber-200 bg-amber-50 p-4 text-sm font-semibold text-amber-800">
          This goal is {goal.status.toLowerCase()} and is not accepting contributions.
        </section>
      ) : (
        <Checkout key={goal.slug} goal={goal} />
      )}

      <section className="mt-4 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4 text-forest-600" />
          <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-slate-500">
            Payout protection
          </h2>
        </div>
        <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-[11px]">
          <dt className="text-slate-500">Destination</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.payoutRules.destinationType === "ADMIN_MOMO"
              ? "Organizer MoMo"
              : "Beneficiary MoMo"}
          </dd>
          <dt className="text-slate-500">Second approver</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.payoutRules.secondApproverName
              ? `${goal.payoutRules.secondApproverName} · `
              : ""}
            {goal.payoutRules.secondApprover.replaceAll("_", " ").toLowerCase()}
          </dd>
          {goal.payoutRules.secondApprover === "ABOVE_THRESHOLD" ? (
            <>
              <dt className="text-slate-500">Second approval above</dt>
              <dd className="text-right font-semibold text-slate-800">
                {formatGhs(goal.payoutRules.secondApproverThresholdPesewas)}
              </dd>
            </>
          ) : null}
          <dt className="text-slate-500">Hold window</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.payoutRules.holdWindowMinutes} minutes
          </dd>
          <dt className="text-slate-500">Surplus</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.payoutRules.surplusRule === "WELFARE_RESERVE"
              ? "Welfare reserve"
              : "Pay beneficiary"}
          </dd>
        </dl>
      </section>

      <section className="mt-4 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-slate-500">
          Recent contributions
        </h2>
        {goal.feed.length === 0 ? (
          <p className="mt-3 text-xs text-slate-500">
            No confirmed contributions yet. Be the first.
          </p>
        ) : (
          <ul className="mt-3 space-y-3">
            {goal.feed.map((item, index) => (
              <li key={`${item.at}-${index}`} className="flex items-center justify-between gap-3">
                <div className="flex min-w-0 items-center gap-3">
                  <span
                    className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-extrabold ${
                      item.anonymous
                        ? "bg-sage-100 text-forest-700"
                        : "bg-forest-600 text-white"
                    }`}
                  >
                    {item.anonymous ? (
                      <User className="h-4 w-4" />
                    ) : (
                      (item.displayName ?? "A").slice(0, 1).toUpperCase()
                    )}
                  </span>
                  <div className="min-w-0">
                    <div className="truncate text-xs font-bold text-slate-900">
                      {item.anonymous
                        ? "An anonymous contributor"
                        : item.displayName ?? "A contributor"}
                    </div>
                    <div className="text-[10px] text-slate-500">
                      {formatDateTime(item.at)}
                    </div>
                  </div>
                </div>
                <div className="font-mono text-xs font-black text-slate-900">
                  {formatGhs(item.amountPesewas)}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <footer className="py-8 text-center text-[11px] text-slate-500">
        Powered by <span className="font-bold text-forest-700">tgthr</span>
      </footer>
    </main>
  );
}
