"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export function LogoutButton() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);

  async function logout() {
    setBusy(true);
    setError(false);
    try {
      const response = await fetch("/api/auth/logout", { method: "POST" });
      if (response.ok) {
        router.push("/");
        router.refresh();
        return;
      }
      setError(true);
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      onClick={logout}
      disabled={busy}
      className="text-xs font-bold text-slate-500 transition hover:text-forest-700 disabled:opacity-60"
    >
      {busy ? "Signing out…" : error ? "Retry sign out" : "Sign out"}
    </button>
  );
}
