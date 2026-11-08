import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { OrganizerNav } from "@/components/organizer-nav";
import { getOrganizerGoals, getOrganizerProfile } from "@/lib/organizer";
import { formatDateTime, formatGhs } from "@/lib/money";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Organizer dashboard",
  robots: { index: false, follow: false },
};

const TEMPLATE_LABELS: Record<string, string> = {
  TRIP: "Trip",
  WEDDING: "Wedding",
  FUNERAL: "Funeral",
  RENT: "Rent",
  COMMUNITY: "Church",
  GIFT: "Gift",
  PARTY: "Party",
};

export default async function OrganizerDashboard() {
  const [profile, goals] = await Promise.all([getOrganizerProfile(), getOrganizerGoals()]);
  if (!profile || !goals) {
    redirect("/signin");
  }

  const raised = goals.reduce((sum, goal) => sum + goal.raisedPesewas, 0);
  const open = goals.filter((goal) => goal.status === "OPEN").length;

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
      <OrganizerNav profile={profile} />

      <section className="mt-8 flex items-start justify-between gap-4">
        <div>
          <span className="text-[10px] font-black uppercase tracking-[0.16em] text-forest-700">
            Organizer dashboard
          </span>
          <h1 className="mt-1 text-3xl font-black leading-tight text-slate-900">
            {profile.displayName ? `Hey ${profile.displayName}` : "Your goals"}
          </h1>
          <p className="mt-1 text-xs text-slate-500">
            {goals.length} goal{goals.length === 1 ? "" : "s"} · {open} open
          </p>
        </div>
        <Link
          href="/organizer/goals/new"
          className="inline-flex shrink-0 items-center justify-center rounded-2xl bg-forest-600 px-4 py-2.5 text-xs font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
        >
          Create a goal
        </Link>
      </section>

      <section className="mt-5 grid grid-cols-2 gap-3">
        <div className="rounded-3xl bg-forest-800 p-4 text-white shadow-soft-card">
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-sage-200">
            Raised across goals
          </span>
          <div className="mt-2 font-mono text-xl font-black">{formatGhs(raised)}</div>
        </div>
        <div className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft-card">
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
            Contributions
          </span>
          <div className="mt-2 font-mono text-xl font-black text-slate-900">
            {goals.reduce((sum, goal) => sum + goal.contributorCount, 0)}
          </div>
        </div>
      </section>

      <section className="mt-5 space-y-3">
        {goals.length === 0 ? (
          <div className="rounded-3xl border border-dashed border-sage-300 bg-white/70 p-6 text-center">
            <p className="text-sm font-bold text-slate-900">No goals yet</p>
            <p className="mt-1 text-xs leading-relaxed text-slate-500">
              Create a goal, share the link, and let the group contribute by mobile money.
            </p>
          </div>
        ) : (
          goals.map((goal) => (
            <Link
              key={goal.id}
              href={`/organizer/goals/${goal.id}`}
              className="block rounded-3xl border border-slate-100 bg-white p-4 shadow-soft-card transition hover:border-sage-300"
            >
              <div className="flex items-start justify-between gap-3">
                <div>
                  <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-forest-700">
                    {TEMPLATE_LABELS[goal.template] ?? goal.template} goal
                  </span>
                  <h2 className="mt-0.5 text-base font-extrabold leading-snug text-slate-900">
                    {goal.title}
                  </h2>
                </div>
                <span
                  className={`rounded-full px-2.5 py-0.5 text-[10px] font-bold ${
                    goal.status === "OPEN"
                      ? "bg-sage-100 text-forest-700"
                      : "bg-slate-100 text-slate-600"
                  }`}
                >
                  {goal.status}
                </span>
              </div>

              <div className="mt-3 flex items-end justify-between gap-3">
                <div>
                  <div className="font-mono text-lg font-black text-slate-900">
                    {formatGhs(goal.raisedPesewas)}
                  </div>
                  <div className="text-[10px] font-medium text-slate-500">
                    {goal.contributorCount} contribution{goal.contributorCount === 1 ? "" : "s"}
                  </div>
                </div>
                <div className="text-right text-[10px] text-slate-500">
                  <div className="font-bold text-slate-700">{goal.destinationAccountName}</div>
                  <div>{goal.deadline ? `Due ${formatDateTime(goal.deadline)}` : "No deadline"}</div>
                </div>
              </div>
            </Link>
          ))
        )}
      </section>
    </main>
  );
}
