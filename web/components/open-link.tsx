"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ArrowRight } from "./icons";

function slugFrom(input: string): string | null {
  const value = input.trim();
  if (!value) {
    return null;
  }
  try {
    const url = new URL(value.includes("://") ? value : `https://tgthr.local/${value}`);
    const segments = url.pathname.split("/").filter(Boolean);
    return segments.at(-1) ?? null;
  } catch {
    return null;
  }
}

export function OpenLinkForm() {
  const router = useRouter();
  const [value, setValue] = useState("");

  function open(event: React.FormEvent) {
    event.preventDefault();
    const slug = slugFrom(value);
    if (slug) {
      router.push(`/g/${encodeURIComponent(slug)}`);
    }
  }

  return (
    <form onSubmit={open} className="flex flex-col gap-3 sm:flex-row">
      <label htmlFor="goal-link" className="sr-only">
        Goal link or slug
      </label>
      <input
        id="goal-link"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        placeholder="Paste the goal link or slug"
        className="w-full flex-1 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-base font-medium text-slate-900 shadow-sm outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15 sm:text-sm"
      />
      <button
        type="submit"
        className="inline-flex items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-700 focus-visible:ring-offset-2"
      >
        Open goal
        <ArrowRight className="h-4 w-4" />
      </button>
    </form>
  );
}
