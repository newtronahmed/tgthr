import type { Metadata } from "next";
import { cookies } from "next/headers";
import { OperatorEnroll } from "@/components/operator-enroll";
import { clearEnrollmentToken, selectEnrollmentToken } from "../auth-actions";

export const metadata: Metadata = {
  title: "Operator enrollment",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

const inputClass =
  "w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15";

export default async function EnrollmentPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string }>;
}) {
  const params = await searchParams;
  const jar = await cookies();
  const token = jar.get("tgthr_operator_enrollment")?.value;

  if (token) {
    return (
      <>
        <OperatorEnroll token={token} />
        <form action={clearEnrollmentToken} className="mx-auto w-full max-w-lg px-5 pb-8 text-center">
          <button
            type="submit"
            className="text-[11px] font-bold text-slate-500 transition hover:text-forest-700"
          >
            Use a different enrollment token
          </button>
        </form>
      </>
    );
  }

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-8">
      <header className="flex items-center gap-2.5">
        <span
          aria-hidden
          className="inline-flex h-9 w-9 items-center justify-center rounded-2xl bg-forest-600 font-black text-white shadow-[0_8px_18px_-6px_rgba(0,104,71,0.55)]"
        >
          t
        </span>
        <div className="leading-tight">
          <span className="font-extrabold tracking-tight text-slate-900">tgthr</span>
          <div className="text-[10px] font-bold uppercase tracking-[0.16em] text-forest-700">
            operator enrollment
          </div>
        </div>
      </header>

      <section className="mt-10 space-y-3">
        <h1 className="text-4xl font-black leading-[1.05] tracking-tight text-slate-900">
          Enter your
          <br />
          enrollment token.
        </h1>
        <p className="max-w-sm text-sm leading-relaxed text-slate-600">
          An operator administrator issues this one-time token. It expires quickly and works once.
        </p>
      </section>

      <section className="mt-8 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        {params.error ? (
          <p className="mb-4 rounded-2xl border border-red-200 bg-red-50 px-3 py-2 text-xs font-semibold text-red-700">
            Enter the token from your administrator.
          </p>
        ) : null}
        <form action={selectEnrollmentToken} className="space-y-4">
          <div>
            <label
              htmlFor="enrollment-token"
              className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
            >
              Enrollment token
            </label>
            <input
              id="enrollment-token"
              name="token"
              type="text"
              autoComplete="off"
              spellCheck={false}
              className={`${inputClass} font-mono text-xs`}
              required
            />
          </div>
          <button
            type="submit"
            className="inline-flex w-full items-center justify-center rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
          >
            Continue
          </button>
        </form>
      </section>
    </main>
  );
}
