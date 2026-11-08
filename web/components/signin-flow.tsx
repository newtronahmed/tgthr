"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { organizerProblemMessage } from "@/lib/problems";
import type { Problem } from "@/lib/types";
import { BrandHeader } from "./brand";
import { ArrowRight, Shield } from "./icons";

const inputClass =
  "w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-base font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15 sm:text-sm";

async function problem(response: Response): Promise<Problem> {
  return (await response.json().catch(() => ({}))) as Problem;
}

function normalizePhone(value: string): string {
  return value.replace(/[\s-]/g, "");
}

export function SignInFlow() {
  const router = useRouter();
  const [step, setStep] = useState<"phone" | "code">("phone");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [legalName, setLegalName] = useState("");
  const [seconds, setSeconds] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (seconds <= 0) {
      return;
    }
    const timer = setTimeout(() => setSeconds((value) => value - 1), 1_000);
    return () => clearTimeout(timer);
  }, [seconds]);

  async function requestCode(event?: React.FormEvent) {
    event?.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/auth/otp/request", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ phone: normalizePhone(phone) }),
      });
      if (response.status === 202) {
        setStep("code");
        setSeconds(60);
        return;
      }
      setError(organizerProblemMessage(await problem(response)));
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/auth/otp/verify", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          phone: normalizePhone(phone),
          code,
          displayName: displayName.trim(),
          legalName: legalName.trim() || undefined,
        }),
      });
      if (response.ok) {
        router.push("/organizer");
        router.refresh();
        return;
      }
      setError(organizerProblemMessage(await problem(response)));
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-8">
      <BrandHeader />

      <section className="mt-10 space-y-3">
        <span className="inline-flex items-center gap-2 rounded-full bg-white px-3 py-1 text-[11px] font-bold text-forest-700 shadow-sm">
          <Shield className="h-4 w-4 text-forest-600" />
          Organizer sign in
        </span>
        <h1 className="text-4xl font-black leading-[1.05] tracking-tight text-slate-900">
          Your goals,
          <br />
          your money.
        </h1>
        <p className="max-w-sm text-sm leading-relaxed text-slate-600">
          Sign in with a code by SMS. No password to forget, no app to install.
        </p>
      </section>

      <section className="mt-8 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        {step === "phone" ? (
          <form onSubmit={requestCode} className="space-y-4">
            <div>
              <label
                htmlFor="phone"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Mobile money number
              </label>
              <input
                id="phone"
                type="tel"
                inputMode="tel"
                autoComplete="tel"
                value={phone}
                onChange={(event) => setPhone(event.target.value)}
                placeholder="024 000 0000"
                className={inputClass}
              />
            </div>
            <button
              type="submit"
              disabled={busy}
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 disabled:opacity-60"
            >
              {busy ? "Sending…" : "Send code"}
              <ArrowRight className="h-4 w-4" />
            </button>
          </form>
        ) : (
          <form onSubmit={verify} className="space-y-4">
            <div className="flex items-center justify-between">
              <p className="text-xs text-slate-500">
                Code sent to <span className="font-bold text-slate-800">{phone}</span>
              </p>
              <button
                type="button"
                onClick={() => {
                  setStep("phone");
                  setCode("");
                  setError(null);
                }}
                className="text-[11px] font-bold text-forest-700 hover:text-forest-800"
              >
                Change
              </button>
            </div>
            <div>
              <label
                htmlFor="code"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Six-digit code
              </label>
              <input
                id="code"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
                placeholder="123456"
                className={`${inputClass} font-mono tracking-[0.25em]`}
              />
            </div>
            <div>
              <label
                htmlFor="displayName"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Display name
              </label>
              <input
                id="displayName"
                autoComplete="name"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Ama"
                className={inputClass}
              />
            </div>
            <div>
              <label
                htmlFor="legalName"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Legal name (optional)
              </label>
              <input
                id="legalName"
                autoComplete="name"
                value={legalName}
                onChange={(event) => setLegalName(event.target.value)}
                placeholder="Ama Mensah"
                className={inputClass}
              />
              <p className="mt-1 text-[10px] leading-relaxed text-slate-500">
                Needed before a goal can pay your own MoMo; it must match the MoMo account name.
              </p>
            </div>
            <button
              type="submit"
              disabled={busy || code.length !== 6 || !displayName.trim()}
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 disabled:opacity-60"
            >
              {busy ? "Verifying…" : "Verify and continue"}
              <ArrowRight className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={() => requestCode()}
              disabled={seconds > 0 || busy}
              className="w-full text-[11px] font-bold text-slate-500 transition hover:text-forest-700 disabled:opacity-60"
            >
              {seconds > 0 ? `Send a new code in ${seconds}s` : "Send a new code"}
            </button>
          </form>
        )}
        {error ? (
          <p role="alert" className="mt-3 rounded-xl bg-amber-50 px-3 py-2 text-xs font-semibold text-amber-800">
            {error}
          </p>
        ) : null}
      </section>

      <p className="mt-6 text-[11px] leading-relaxed text-slate-500">
        By continuing you agree to keep contributions and payouts on the records shown here.
      </p>
    </main>
  );
}
