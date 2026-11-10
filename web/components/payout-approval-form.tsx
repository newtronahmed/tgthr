"use client";

import { useState } from "react";
import { formatGhs } from "@/lib/money";
import type { PayoutApprovalView } from "@/lib/types";

export function PayoutApprovalForm({
  token,
  approval,
}: {
  token: string;
  approval: PayoutApprovalView;
}) {
  const [code, setCode] = useState("");
  const [state, setState] = useState(approval.state);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(`/api/payout-approvals/${encodeURIComponent(token)}/verify`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ code }),
      });
      if (!response.ok) {
        setMessage("The code is invalid, expired or already used.");
        return;
      }
      const result = (await response.json()) as { state: string };
      setState(result.state);
      setMessage("Approved. The payout is now in its hold window.");
    } catch {
      setMessage("The approval could not be completed. Try again shortly.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center px-5 py-8">
      <section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft-card">
        <p className="text-[10px] font-extrabold uppercase tracking-[0.12em] text-forest-700">
          Payout approval
        </p>
        <h1 className="mt-1 text-2xl font-black text-slate-900">{approval.goalTitle}</h1>
        <p className="mt-1 text-xs text-slate-500">Approver: {approval.approverName}</p>
        <dl className="mt-4 grid grid-cols-2 gap-2 rounded-2xl bg-slate-50 p-3 text-[11px]">
          <dt className="text-slate-500">Amount</dt>
          <dd className="text-right font-mono font-bold text-slate-900">
            {formatGhs(approval.amountPesewas)}
          </dd>
          <dt className="text-slate-500">Fee</dt>
          <dd className="text-right font-mono font-bold text-slate-900">
            {formatGhs(approval.feePesewas)}
          </dd>
          <dt className="text-slate-500">Beneficiary net</dt>
          <dd className="text-right font-mono font-bold text-slate-900">
            {formatGhs(approval.netPesewas)}
          </dd>
        </dl>
        {state === "HOLD" ? (
          <p className="mt-4 rounded-xl bg-sage-100 p-3 text-xs font-bold text-forest-800">
            Approved. The payout is now in its hold window.
          </p>
        ) : (
          <form onSubmit={verify} className="mt-4">
            <label className="block text-[10px] font-bold uppercase tracking-[0.08em] text-slate-500">
              Six-digit code
              <input
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                className="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 font-mono text-lg tracking-[0.3em] text-slate-900 outline-none focus:border-forest-600"
              />
            </label>
            <button
              type="submit"
              disabled={busy || code.length !== 6}
              className="mt-3 w-full rounded-xl bg-forest-700 px-4 py-2.5 text-xs font-extrabold text-white disabled:opacity-60"
            >
              {busy ? "Approving…" : "Approve payout"}
            </button>
          </form>
        )}
        {message ? <p className="mt-3 text-[11px] font-semibold text-slate-600">{message}</p> : null}
      </section>
    </main>
  );
}
