import { BrandHeader } from "@/components/brand";
import { Check, Phone } from "@/components/icons";
import { RefreshButton } from "@/components/refresh-button";
import { StatusPoller } from "@/components/status-poller";
import { getContribution } from "@/lib/api";
import { formatDateTime, formatGhs } from "@/lib/money";
import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Your contribution",
  robots: { index: false, follow: false },
};

function FeeRows({
  amountPesewas,
  processingFeePesewas,
  platformFeePesewas,
  totalPesewas,
}: {
  amountPesewas: number;
  processingFeePesewas: number;
  platformFeePesewas: number;
  totalPesewas: number;
}) {
  return (
    <div className="space-y-2 rounded-2xl border border-slate-200 bg-white p-4 font-mono text-xs shadow-soft-card">
      <div className="flex justify-between text-slate-500">
        <span className="font-sans">Contribution</span>
        <span>{formatGhs(amountPesewas)}</span>
      </div>
      <div className="flex justify-between text-slate-500">
        <span className="font-sans">MoMo processing</span>
        <span>{formatGhs(processingFeePesewas)}</span>
      </div>
      <div className="flex justify-between text-slate-500">
        <span className="font-sans">Platform fee (1.5%)</span>
        <span>{formatGhs(platformFeePesewas)}</span>
      </div>
      <div className="flex justify-between border-t border-slate-100 pt-2 font-bold text-slate-900">
        <span className="font-sans">Total charged</span>
        <span className="text-forest-700">{formatGhs(totalPesewas)}</span>
      </div>
    </div>
  );
}

export default async function ContributionPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ goal?: string }>;
}) {
  const { id } = await params;
  const { goal: goalParam } = await searchParams;
  const goal = Array.isArray(goalParam) ? goalParam[0] : goalParam;
  const status = await getContribution(id);
  if (!status) {
    notFound();
  }

  const backHref = goal ? `/g/${encodeURIComponent(goal)}` : "/";

  if (status.state === "CONFIRMED") {
    return (
      <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
        <BrandHeader />
        <section className="animate-rise mt-8 rounded-3xl border border-slate-100 bg-white p-6 shadow-soft-card">
          <div className="flex flex-col items-center text-center">
            <span className="flex h-14 w-14 items-center justify-center rounded-full border border-emerald-300 bg-emerald-100 text-emerald-700">
              <Check className="h-6 w-6" />
            </span>
            <span className="mt-3 text-[10px] font-bold uppercase tracking-[0.16em] text-forest-600">
              Transaction confirmed
            </span>
            <span className="mt-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500">
              Total charged
            </span>
            <h1 className="font-mono text-3xl font-black text-slate-900">
              {formatGhs(status.totalPesewas)}
            </h1>
            <p className="mt-1 font-mono text-[11px] text-slate-500">
              Ref: {status.receipt?.reference ?? status.id}
            </p>
          </div>

          <div className="mt-5">
            <FeeRows
              amountPesewas={status.amountPesewas}
              processingFeePesewas={status.processingFeePesewas}
              platformFeePesewas={status.platformFeePesewas}
              totalPesewas={status.totalPesewas}
            />
          </div>

          <dl className="mt-3 space-y-1.5 px-1 text-[11px]">
            <div className="flex justify-between">
              <dt className="text-slate-500">Confirmed</dt>
              <dd className="font-semibold text-slate-800">
                {status.receipt?.confirmedAt
                  ? formatDateTime(status.receipt.confirmedAt)
                  : "—"}
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-slate-500">Ledger</dt>
              <dd className="font-bold text-emerald-700">POSTED</dd>
            </div>
          </dl>
        </section>

        <div className="mt-4 flex flex-col gap-2">
          <Link
            href={backHref}
            className="inline-flex items-center justify-center rounded-2xl bg-forest-600 py-3 text-xs font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
          >
            Back to the goal
          </Link>
        </div>

        <footer className="mt-auto py-8 text-center text-[11px] text-slate-500">
          Keep this page — it is your receipt.
        </footer>
      </main>
    );
  }

  if (status.state === "PENDING" || status.state === "INITIATED") {
    return (
      <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
        <BrandHeader />
        <StatusPoller id={status.id} />
        <section className="animate-rise mt-8 rounded-3xl border border-slate-100 bg-white p-6 text-center shadow-soft-card">
          <span className="prompt-ring mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-sage-100 text-forest-700">
            <Phone className="h-7 w-7" />
          </span>
          <span className="mt-4 inline-flex rounded-full border border-amber-300 bg-amber-100 px-3 py-0.5 text-[10px] font-bold text-amber-800">
            WAITING FOR APPROVAL
          </span>
          <h1 className="mt-1 text-lg font-black text-slate-900">Check your phone</h1>
          <p className="mt-1 text-xs leading-relaxed text-slate-500">
            Approve the mobile money prompt. This page updates automatically once the payment
            result arrives.
          </p>
          <div className="mt-5 text-left">
            <FeeRows
              amountPesewas={status.amountPesewas}
              processingFeePesewas={status.processingFeePesewas}
              platformFeePesewas={status.platformFeePesewas}
              totalPesewas={status.totalPesewas}
            />
          </div>
          <p className="mt-4 text-[11px] leading-relaxed text-slate-500">
            You can close this page and reopen this link later; it keeps checking for the result.
          </p>
        </section>
      </main>
    );
  }

  const refunded = status.state === "REFUNDED";

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
      <BrandHeader />
      <section className="animate-rise mt-8 rounded-3xl border border-slate-100 bg-white p-6 text-center shadow-soft-card">
        <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full border border-amber-200 bg-amber-100 text-amber-700">
          <Phone className="h-6 w-6" />
        </span>
        <h1 className="mt-3 text-lg font-black text-slate-900">
          {refunded ? "This contribution was refunded" : "We have not recorded a successful payment"}
        </h1>
        <p className="mt-1 text-xs leading-relaxed text-slate-500">
          {refunded
            ? "The money was returned by the organizer. Contact them if you need the details."
            : "If you approved a prompt, a late confirmation can still arrive. Check again before starting a new payment."}
        </p>
        <div className="mt-5 space-y-2">
          <RefreshButton />
          {!refunded ? (
            <Link
              href={backHref}
              className="inline-flex w-full items-center justify-center rounded-2xl bg-forest-600 py-3 text-xs font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
            >
              Start a new contribution
            </Link>
          ) : null}
          <Link href={backHref} className="block text-[11px] font-semibold text-slate-500">
            Back to the goal
          </Link>
        </div>
      </section>
    </main>
  );
}
