"use client";

import { useEffect, useState } from "react";
import { formatDateTime, formatGhs, parseGhsToPesewas } from "@/lib/money";
import type { PayoutView } from "@/lib/types";

export function OrganizerPayouts({ goalId }: { goalId: string }) {
  const [payouts, setPayouts] = useState<PayoutView[]>([]);
  const [amount, setAmount] = useState("10.00");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    const response = await fetch(`/api/organizer/goals/${encodeURIComponent(goalId)}/payouts`, {
      cache: "no-store",
    });
    if (response.ok) {
      setPayouts((await response.json()) as PayoutView[]);
    }
  }

  useEffect(() => {
    let cancelled = false;
    fetch(`/api/organizer/goals/${encodeURIComponent(goalId)}/payouts`, { cache: "no-store" })
      .then(async (response) => {
        if (!cancelled && response.ok) {
          setPayouts((await response.json()) as PayoutView[]);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [goalId]);

  async function request(event: React.FormEvent) {
    event.preventDefault();
    const amountPesewas = parseGhsToPesewas(amount);
    if (amountPesewas === null || amountPesewas < 1) {
      setMessage("Enter a valid payout amount.");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(`/api/organizer/goals/${encodeURIComponent(goalId)}/payouts`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": crypto.randomUUID(),
        },
        body: JSON.stringify({ amountPesewas }),
      });
      if (!response.ok) {
        setMessage("The payout could not be requested. Check the amount and try again.");
        return;
      }
      const payout = (await response.json()) as PayoutView;
      setMessage(
        payout.state === "REQUESTED"
          ? "Awaiting the designated approver's SMS approval."
          : "Payout is in its hold window.",
      );
      await load();
    } catch {
      setMessage("The payout could not be requested. Try again shortly.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-4 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
      <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-slate-500">
        Payouts
      </h2>
      {payouts.length === 0 ? (
        <p className="mt-3 text-xs text-slate-500">No payout requests yet.</p>
      ) : (
        <ul className="mt-3 space-y-2 text-[11px]">
          {payouts.map((payout) => (
            <li key={payout.id} className="rounded-xl bg-slate-50 px-3 py-2">
              <div className="flex justify-between font-semibold text-slate-800">
                <span>{formatGhs(payout.amountPesewas)}</span>
                <span>{payout.state.toLowerCase()}</span>
              </div>
              <div className="text-slate-500">
                fee {formatGhs(payout.feePesewas)} · net {formatGhs(payout.netPesewas)}
                {payout.state === "REQUESTED"
                  ? " · awaiting approval"
                  : payout.state === "HOLD"
                    ? " · in hold"
                    : ""}
                {payout.createdAt ? ` · requested ${formatDateTime(payout.createdAt)}` : ""}
              </div>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={request} className="mt-4 flex items-end gap-2">
        <label className="flex-1 text-[10px] font-bold uppercase tracking-[0.08em] text-slate-500">
          Payout amount (GHS)
          <input
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            inputMode="decimal"
            className="mt-1 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-forest-600"
          />
        </label>
        <button
          type="submit"
          disabled={busy}
          className="rounded-xl bg-forest-700 px-3 py-2 text-[11px] font-extrabold text-white disabled:opacity-60"
        >
          {busy ? "Requesting…" : "Request payout"}
        </button>
      </form>
      {message ? <p className="mt-3 text-[11px] font-semibold text-slate-600">{message}</p> : null}
    </section>
  );
}
