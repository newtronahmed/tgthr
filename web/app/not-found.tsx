import { BrandHeader } from "@/components/brand";
import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-8">
      <BrandHeader />
      <div className="mt-16 rounded-3xl border border-slate-100 bg-white p-6 shadow-soft-card">
        <span className="text-[11px] font-bold uppercase tracking-[0.14em] text-forest-700">
          404
        </span>
        <h1 className="mt-2 text-2xl font-black text-slate-900">This page is not here</h1>
        <p className="mt-2 text-sm leading-relaxed text-slate-500">
          The link may be mistyped or the goal may have been removed. Ask the organizer for a
          fresh link.
        </p>
        <Link
          href="/"
          className="mt-6 inline-flex rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
        >
          Back to tgthr
        </Link>
      </div>
    </main>
  );
}
