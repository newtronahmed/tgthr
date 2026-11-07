"use client";

export default function ErrorPage({ reset }: { error: Error; reset: () => void }) {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col justify-center px-5 py-8">
      <div className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft-card">
        <span className="text-[11px] font-bold uppercase tracking-[0.14em] text-forest-700">
          Something went wrong
        </span>
        <h1 className="mt-2 text-2xl font-black text-slate-900">We couldn&apos;t load this page</h1>
        <p className="mt-2 text-sm leading-relaxed text-slate-500">
          The network or the server had a problem. Nothing was charged from this screen.
        </p>
        <button
          type="button"
          onClick={reset}
          className="mt-6 inline-flex rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
        >
          Try again
        </button>
      </div>
    </main>
  );
}
