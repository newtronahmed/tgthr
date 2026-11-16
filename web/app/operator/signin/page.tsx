import type { Metadata } from "next";
import Link from "next/link";
import { cookies } from "next/headers";
import { login, mfa, recover, resetChallenge } from "../auth-actions";
import { ArrowRight, Shield } from "@/components/icons";
import { operatorProblemMessage } from "@/lib/problems";

export const metadata: Metadata = {
  title: "Operator sign in",
  robots: { index: false, follow: false },
};

const inputClass =
  "w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-base font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15 sm:text-sm";

export default async function OperatorSignInPage({
  searchParams,
}: {
  searchParams: Promise<{ step?: string; mode?: string; error?: string }>;
}) {
  const params = await searchParams;
  const jar = await cookies();
  const challenge = jar.get("tgthr_operator_challenge")?.value;
  const username = jar.get("tgthr_operator_username")?.value ?? "";
  const recovery = params.mode === "recovery";
  const step = !recovery && params.step === "mfa" && challenge ? "mfa" : "password";

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
            operator console
          </div>
        </div>
      </header>

      <section className="mt-10 space-y-3">
        <span className="inline-flex items-center gap-2 rounded-full bg-white px-3 py-1 text-[11px] font-bold text-forest-700 shadow-sm">
          <Shield className="h-4 w-4 text-forest-600" />
          Operator sign in
        </span>
        <h1 className="text-4xl font-black leading-[1.05] tracking-tight text-slate-900">
          Move money
          <br />
          with care.
        </h1>
        <p className="max-w-sm text-sm leading-relaxed text-slate-600">
          Password plus an authenticator code. Every refund and reconciliation action is attributed
          to you.
        </p>
      </section>

      <section className="mt-8 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        {params.error ? (
          <p className="mb-4 rounded-2xl border border-red-200 bg-red-50 px-3 py-2 text-xs font-semibold text-red-700">
            {params.error === "UNAVAILABLE"
              ? "Could not reach tgthr. Try again."
              : operatorProblemMessage({ code: params.error })}
          </p>
        ) : null}

        {recovery ? (
          <form action={recover} className="space-y-4">
            <div>
              <label
                htmlFor="recovery-username"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Username
              </label>
              <input
                id="recovery-username"
                name="username"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                className={inputClass}
                required
              />
            </div>
            <div>
              <label
                htmlFor="recovery-password"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Password
              </label>
              <input
                id="recovery-password"
                name="password"
                type="password"
                autoComplete="current-password"
                className={inputClass}
                required
              />
            </div>
            <div>
              <label
                htmlFor="recovery-code"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Recovery code
              </label>
              <input
                id="recovery-code"
                name="recoveryCode"
                type="text"
                autoComplete="off"
                spellCheck={false}
                className={`${inputClass} font-mono text-xs`}
                required
              />
            </div>
            <p className="text-[11px] leading-relaxed text-slate-500">
              One unused code signs you in once. The code is consumed even if a later step fails.
            </p>
            <button
              type="submit"
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
            >
              Sign in with a recovery code
              <ArrowRight className="h-4 w-4" />
            </button>
            <Link
              href="/operator/signin"
              className="block text-center text-[11px] font-bold text-forest-700 hover:text-forest-800"
            >
              Back to password sign in
            </Link>
          </form>
        ) : step === "password" ? (
          <form action={login} className="space-y-4">
            <div>
              <label
                htmlFor="operator-username"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Username
              </label>
              <input
                id="operator-username"
                name="username"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                className={inputClass}
                required
              />
            </div>
            <div>
              <label
                htmlFor="operator-password"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Password
              </label>
              <input
                id="operator-password"
                name="password"
                type="password"
                autoComplete="current-password"
                className={inputClass}
                required
              />
            </div>
            <button
              type="submit"
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
            >
              Continue
              <ArrowRight className="h-4 w-4" />
            </button>
          </form>
        ) : (
          <form action={mfa} className="space-y-4">
            <div className="flex items-center justify-between gap-3">
              <p className="text-xs text-slate-500">
                Signing in as <span className="font-bold text-slate-800">{username}</span>
              </p>
              <button
                type="submit"
                formAction={resetChallenge}
                className="text-[11px] font-bold text-forest-700 hover:text-forest-800"
              >
                Change
              </button>
            </div>
            <div>
              <label
                htmlFor="operator-code"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Authenticator code
              </label>
              <input
                id="operator-code"
                name="code"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                placeholder="123456"
                className={`${inputClass} font-mono tracking-[0.35em]`}
                required
              />
            </div>
            <button
              type="submit"
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700"
            >
              Sign in
              <ArrowRight className="h-4 w-4" />
            </button>
          </form>
        )}
      </section>

      {!recovery && step === "password" ? (
        <p className="mt-4 text-center text-[11px] text-slate-500">
          Lost your authenticator?{" "}
          <Link
            href="/operator/signin?mode=recovery"
            className="font-bold text-forest-700 hover:text-forest-800"
          >
            Use a recovery code
          </Link>
        </p>
      ) : null}

      <p className="mt-5 text-center text-[11px] text-slate-500">
        Have a one-time enrollment token?{" "}
        <Link href="/operator/enroll" className="font-bold text-forest-700 hover:text-forest-800">
          Enroll your account
        </Link>
      </p>
    </main>
  );
}
