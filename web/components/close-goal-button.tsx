"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { organizerProblemMessage } from "@/lib/problems";
import type { Problem } from "@/lib/types";

export function CloseGoalButton({ goalId }: { goalId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function close() {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/organizer/goals/${encodeURIComponent(goalId)}/close`, {
        method: "POST",
      });
      if (response.status === 401) {
        router.push("/signin");
        return;
      }
      if (response.ok) {
        router.refresh();
        return;
      }
      const problem = (await response.json().catch(() => ({}))) as Problem;
      setError(organizerProblemMessage(problem));
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2">
      {!confirming ? (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          disabled={busy}
          className="w-full rounded-2xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm font-extrabold text-amber-900 transition hover:bg-amber-100 disabled:opacity-60"
        >
          Close goal
        </button>
      ) : (
        <div className="space-y-2 rounded-2xl border border-amber-300 bg-amber-50 p-3">
          <p className="text-xs font-semibold text-amber-900">
            Closing stops new contributions. This cannot be undone.
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setConfirming(false)}
              disabled={busy}
              className="flex-1 rounded-xl bg-white px-3 py-2 text-xs font-bold text-slate-700"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={close}
              disabled={busy}
              className="flex-1 rounded-xl bg-amber-700 px-3 py-2 text-xs font-bold text-white disabled:opacity-60"
            >
              {busy ? "Closing…" : "Confirm close"}
            </button>
          </div>
        </div>
      )}
      {error ? (
        <p role="alert" className="text-xs font-semibold text-amber-800">
          {error}
        </p>
      ) : null}
    </div>
  );
}
