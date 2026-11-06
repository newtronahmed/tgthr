"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export function RefreshButton({ label = "Check again" }: { label?: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);

  return (
    <button
      type="button"
      disabled={busy}
      onClick={() => {
        setBusy(true);
        router.refresh();
        setTimeout(() => setBusy(false), 1_500);
      }}
      className="inline-flex w-full items-center justify-center rounded-2xl bg-slate-100 py-2.5 text-xs font-bold text-slate-700 transition hover:bg-slate-200 disabled:opacity-60"
    >
      {busy ? "Checking…" : label}
    </button>
  );
}
