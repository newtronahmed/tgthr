import Link from "next/link";
import { BrandHeader, BrandMark } from "@/components/brand";
import { Shield, WhatsApp } from "@/components/icons";
import { OpenLinkForm } from "@/components/open-link";

export default function HomePage() {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-8">
      <BrandHeader />

      <section className="mt-12 space-y-4">
        <span className="inline-flex items-center gap-2 rounded-full bg-white px-3 py-1 text-[11px] font-bold text-forest-700 shadow-sm">
          <WhatsApp className="h-4 w-4 text-emerald-500" />
          Arrived from a WhatsApp link?
        </span>
        <h1 className="text-4xl font-black leading-[1.05] tracking-tight text-slate-900">
          Group money,
          <br />
          without the chaos.
        </h1>
        <p className="max-w-sm text-sm leading-relaxed text-slate-600">
          Open the link you were sent to contribute by mobile money. No app, no account,
          no group chat arithmetic.
        </p>
      </section>

      <section className="mt-8 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <h2 className="text-xs font-bold uppercase tracking-[0.14em] text-slate-500">
          Have a link?
        </h2>
        <div className="mt-3">
          <OpenLinkForm />
        </div>
        <p className="mt-3 text-[11px] text-slate-500">
          A goal link looks like <span className="font-mono">tgthr.app/g/maldives-trip</span>.
        </p>
      </section>

      <section className="mt-10 grid gap-3 sm:grid-cols-2">
        <div className="rounded-3xl bg-forest-800 p-5 text-white shadow-soft-card">
          <BrandMark size={30} />
          <h3 className="mt-4 text-sm font-extrabold">Exactly what you pay</h3>
          <p className="mt-1 text-xs leading-relaxed text-sage-200">
            The amount, MoMo processing and the 1.5% platform fee are shown before you approve
            the prompt.
          </p>
        </div>
        <div className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
          <Shield className="h-6 w-6 text-forest-600" />
          <h3 className="mt-4 text-sm font-extrabold text-slate-900">Every cedi recorded</h3>
          <p className="mt-1 text-xs leading-relaxed text-slate-500">
            Confirmed contributions are written to an append-only ledger, once, even if the
            network repeats itself.
          </p>
        </div>
      </section>

      <footer className="mt-auto flex items-center justify-between gap-3 pt-12 text-[11px] text-slate-500">
        <span>tgthr · group money, simplified</span>
        <Link href="/signin" className="font-bold text-forest-700 hover:text-forest-800">
          Organizer sign in
        </Link>
      </footer>
    </main>
  );
}
