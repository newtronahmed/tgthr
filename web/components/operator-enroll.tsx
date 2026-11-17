"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { operatorProblemMessage } from "@/lib/problems";
import type { Problem } from "@/lib/types";
import { ArrowRight, Check, Copy, Shield } from "./icons";

const inputClass =
  "w-full rounded-2xl border border-slate-200 bg-white px-4 py-3 text-base font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15 sm:text-sm";

type Details = {
  totpSecret: string;
  otpauthUri: string;
  recoveryCodes: string[];
};

async function problem(response: Response): Promise<Problem> {
  return (await response.json().catch(() => ({}))) as Problem;
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          setTimeout(() => setCopied(false), 1_500);
        } catch {
          setCopied(false);
        }
      }}
      className="inline-flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-forest-700 hover:text-forest-800"
    >
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      {copied ? "Copied" : label}
    </button>
  );
}

export function OperatorEnroll({ token }: { token: string }) {
  const router = useRouter();
  const [step, setStep] = useState<"password" | "confirm">("password");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [code, setCode] = useState("");
  const [details, setDetails] = useState<Details | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function setPasswordStep(event: React.FormEvent) {
    event.preventDefault();
    if (password !== confirmPassword) {
      setError("The passwords do not match.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/operator/api/auth/enroll", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ token, password }),
      });
      if (response.ok) {
        setDetails((await response.json()) as Details);
        setStep("confirm");
        return;
      }
      setError(operatorProblemMessage(await problem(response)));
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function confirmStep(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/operator/api/auth/enroll/confirm", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ token, code }),
      });
      if (response.ok) {
        router.push("/operator/signin");
        router.refresh();
        return;
      }
      setError(operatorProblemMessage(await problem(response)));
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
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
        <span className="inline-flex items-center gap-2 rounded-full bg-white px-3 py-1 text-[11px] font-bold text-forest-700 shadow-sm">
          <Shield className="h-4 w-4 text-forest-600" />
          One-time setup
        </span>
        <h1 className="text-4xl font-black leading-[1.05] tracking-tight text-slate-900">
          Set up your
          <br />
          authenticator.
        </h1>
        <p className="max-w-sm text-sm leading-relaxed text-slate-600">
          This link works once. Save the secret and recovery codes somewhere safe before you
          confirm.
        </p>
      </section>

      <section className="mt-8 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        {step === "password" ? (
          <form onSubmit={setPasswordStep} className="space-y-4">
            <div>
              <label
                htmlFor="enroll-password"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                New password
              </label>
              <input
                id="enroll-password"
                type="password"
                autoComplete="new-password"
                minLength={12}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                className={inputClass}
                required
              />
            </div>
            <div>
              <label
                htmlFor="enroll-password-confirm"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Confirm password
              </label>
              <input
                id="enroll-password-confirm"
                type="password"
                autoComplete="new-password"
                minLength={12}
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                className={inputClass}
                required
              />
            </div>
            <p className="text-[11px] leading-relaxed text-slate-500">
              12 characters or more. Use a password manager; there is no email reset.
            </p>
            {error ? <p className="text-xs font-semibold text-red-600">{error}</p> : null}
            <button
              type="submit"
              disabled={busy}
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 disabled:opacity-60"
            >
              {busy ? "Preparing…" : "Continue"}
              <ArrowRight className="h-4 w-4" />
            </button>
          </form>
        ) : (
          <form onSubmit={confirmStep} className="space-y-4">
            {details ? (
              <div className="space-y-3 rounded-2xl bg-sage-50 p-4">
                <div>
                  <div className="mb-1 flex items-center justify-between gap-3">
                    <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                      Authenticator secret
                    </span>
                    <CopyButton value={details.totpSecret} label="Copy secret" />
                  </div>
                  <p
                    data-testid="operator-totp-secret"
                    className="break-all font-mono text-xs font-bold text-slate-900"
                  >
                    {details.totpSecret}
                  </p>
                </div>
                <div>
                  <div className="mb-1 flex items-center justify-between gap-3">
                    <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                      Setup link
                    </span>
                    <CopyButton value={details.otpauthUri} label="Copy link" />
                  </div>
                  <p className="break-all font-mono text-[10px] leading-relaxed text-slate-600">
                    {details.otpauthUri}
                  </p>
                </div>
                <div>
                  <div className="mb-1 flex items-center justify-between gap-3">
                    <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500">
                      Recovery codes
                    </span>
                    <CopyButton
                      value={details.recoveryCodes.join("\n")}
                      label="Copy all"
                    />
                  </div>
                  <ul className="grid grid-cols-2 gap-1 font-mono text-[11px] text-slate-800">
                    {details.recoveryCodes.map((recovery) => (
                      <li key={recovery}>{recovery}</li>
                    ))}
                  </ul>
                  <p className="mt-2 text-[10px] font-semibold text-amber-700">
                    These are shown once. Store them offline.
                  </p>
                </div>
              </div>
            ) : null}
            <div>
              <label
                htmlFor="enroll-code"
                className="mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500"
              >
                Code from your authenticator
              </label>
              <input
                id="enroll-code"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
                placeholder="123456"
                className={`${inputClass} font-mono tracking-[0.35em]`}
                required
              />
            </div>
            {error ? <p className="text-xs font-semibold text-red-600">{error}</p> : null}
            <button
              type="submit"
              disabled={busy}
              className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 disabled:opacity-60"
            >
              {busy ? "Confirming…" : "Finish enrollment"}
              <ArrowRight className="h-4 w-4" />
            </button>
          </form>
        )}
      </section>
    </main>
  );
}
