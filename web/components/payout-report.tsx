"use client";

import { useState } from "react";

const inputClass =
  "mt-2 w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-900 outline-none focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15";

export function PayoutReportButton({ slug, payoutId }: { slug: string; payoutId: string }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const response = await fetch(
        `/api/goals/${encodeURIComponent(slug)}/payouts/${encodeURIComponent(payoutId)}/report`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ reason }),
        },
      );
      if (response.ok) {
        setReason("");
        setOpen(false);
        setMessage("Report received. An operator will review it.");
      } else {
        setMessage("The report could not be sent. Try again shortly.");
      }
    } catch {
      setMessage("The report could not be sent. Try again shortly.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-1">
      {open ? (
        <form onSubmit={submit} className="rounded-xl border border-amber-200 bg-amber-50 p-2">
          <label htmlFor={`reason-${payoutId}`} className="text-[10px] font-bold text-amber-900">
            What looks wrong?
          </label>
          <textarea
            id={`reason-${payoutId}`}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={500}
            required
            rows={2}
            className={inputClass}
          />
          <div className="mt-2 flex gap-2">
            <button
              type="submit"
              disabled={busy}
              className="rounded-lg bg-amber-700 px-2.5 py-1 text-[10px] font-bold text-white disabled:opacity-60"
            >
              {busy ? "Sending…" : "Send report"}
            </button>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="rounded-lg border border-amber-300 px-2.5 py-1 text-[10px] font-bold text-amber-900"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="text-[10px] font-bold text-amber-800 underline decoration-amber-400 underline-offset-2"
        >
          Report a problem
        </button>
      )}
      {message ? <p className="mt-1 text-[10px] text-slate-600">{message}</p> : null}
    </div>
  );
}
