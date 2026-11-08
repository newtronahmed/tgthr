import type { Metadata } from "next";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { CloseGoalButton } from "@/components/close-goal-button";
import { OrganizerNav } from "@/components/organizer-nav";
import { formatDateTime, formatGhs } from "@/lib/money";
import { getOrganizerGoal, getOrganizerProfile } from "@/lib/organizer";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Goal",
  robots: { index: false, follow: false },
};

export default async function OrganizerGoalPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const [profile, result] = await Promise.all([
    getOrganizerProfile(),
    getOrganizerGoal(id),
  ]);
  if (!profile || result.kind === "unauthorized") {
    redirect("/signin");
  }
  if (result.kind === "not_found") {
    notFound();
  }
  const goal = result.goal;

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
      <OrganizerNav profile={profile} />

      <section className="mt-6 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <div className="flex items-start justify-between gap-3">
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-forest-700">
            {goal.template} goal
          </span>
          <span
            data-testid="goal-status"
            className={`rounded-full px-2.5 py-0.5 text-[10px] font-bold ${
              goal.status === "OPEN" ? "bg-sage-100 text-forest-700" : "bg-slate-100 text-slate-600"
            }`}
          >
            {goal.status}
          </span>
        </div>
        <h1 className="mt-1 text-2xl font-black leading-tight text-slate-900">{goal.title}</h1>
        <p className="mt-1 text-xs text-slate-500">
          {goal.deadline ? `Due ${formatDateTime(goal.deadline)}` : "No deadline"}
        </p>

        <div className="mt-4 flex items-end justify-between gap-3">
          <div>
            <div className="font-mono text-2xl font-black text-slate-900">
              {formatGhs(goal.raisedPesewas)}
            </div>
            <div className="text-[11px] text-slate-500">raised</div>
          </div>
          <div className="rounded-full bg-sage-100 px-3 py-1 text-[10px] font-bold text-forest-700">
            {goal.contributorCount} contribution{goal.contributorCount === 1 ? "" : "s"}
          </div>
        </div>

        <Link
          href={`/g/${goal.slug}`}
          className="mt-4 inline-flex w-full items-center justify-center rounded-2xl border border-sage-300 bg-sage-50 px-4 py-2.5 text-xs font-extrabold text-forest-800 transition hover:bg-sage-100"
        >
          View public page
        </Link>
      </section>

      <section className="mt-4 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-slate-500">
          Payout protection
        </h2>
        <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-[11px]">
          <dt className="text-slate-500">Destination</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.destinationAccountName} · {goal.destinationType.replaceAll("_", " ")}
          </dd>
          <dt className="text-slate-500">Second approver</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.secondApprover.replaceAll("_", " ").toLowerCase()}
            {goal.secondApprover === "ABOVE_THRESHOLD"
              ? ` above ${formatGhs(goal.secondApproverThresholdPesewas)}`
              : ""}
          </dd>
          <dt className="text-slate-500">Hold window</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.holdWindowMinutes} minutes
          </dd>
          <dt className="text-slate-500">Surplus</dt>
          <dd className="text-right font-semibold text-slate-800">
            {goal.surplusRule === "WELFARE_RESERVE" ? "Welfare reserve" : "Pay beneficiary"}
          </dd>
        </dl>
      </section>

      <div className="mt-4">
        {goal.status === "OPEN" ? (
          <CloseGoalButton goalId={goal.id} />
        ) : (
          <p className="rounded-2xl bg-slate-100 px-4 py-3 text-center text-xs font-bold text-slate-600">
            This goal is {goal.status.toLowerCase()}.
          </p>
        )}
      </div>

      <Link
        href="/organizer"
        className="mt-4 text-center text-[11px] font-bold text-slate-500 hover:text-forest-700"
      >
        Back to dashboard
      </Link>
    </main>
  );
}
