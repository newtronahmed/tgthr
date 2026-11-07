"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { formatGhs, parseGhsToPesewas } from "@/lib/money";
import { problemMessage } from "@/lib/problems";
import type {
  ContributionStatus,
  FeeQuote,
  Network,
  Problem,
  PublicGoal,
} from "@/lib/types";
import { Bolt } from "./icons";

const NETWORKS: { value: Network; label: string }[] = [
  { value: "MTN", label: "MTN" },
  { value: "TELECEL", label: "Telecel" },
  { value: "AT", label: "AT" },
];

const QUICK_AMOUNTS_GHS = [100, 250, 500];

const inputClass =
  "w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-base text-slate-900 outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15 sm:text-sm";

function FeeBreakdown({
  amountPesewas,
  processingFeePesewas,
  platformFeePesewas,
  totalPesewas,
}: FeeQuote) {
  return (
    <div className="space-y-1.5 rounded-2xl border border-sage-200 bg-sage-100 p-3.5 font-mono text-xs">
      <div className="flex justify-between text-slate-600">
        <span className="font-sans">Contribution</span>
        <span>{formatGhs(amountPesewas)}</span>
      </div>
      <div className="flex justify-between text-slate-600">
        <span className="font-sans">MoMo processing</span>
        <span>{formatGhs(processingFeePesewas)}</span>
      </div>
      <div className="flex justify-between text-slate-600">
        <span className="font-sans">Platform fee (1.5%)</span>
        <span>{formatGhs(platformFeePesewas)}</span>
      </div>
      <div className="flex justify-between border-t border-sage-200 pt-1.5 font-bold text-slate-900">
        <span className="font-sans">Total charged</span>
        <span className="text-forest-700">{formatGhs(totalPesewas)}</span>
      </div>
    </div>
  );
}

function idempotencyKey(goalSlug: string, signature: string): string {
  const storageKey = `tgthr:idem:${goalSlug}`;
  try {
    const raw = window.sessionStorage.getItem(storageKey);
    if (raw) {
      const stored = JSON.parse(raw) as { key: string; signature: string };
      if (stored.signature === signature && stored.key) {
        return stored.key;
      }
    }
    const key = crypto.randomUUID();
    window.sessionStorage.setItem(storageKey, JSON.stringify({ key, signature }));
    return key;
  } catch {
    return crypto.randomUUID();
  }
}

function clearIdempotencyKey(goalSlug: string): void {
  try {
    window.sessionStorage.removeItem(`tgthr:idem:${goalSlug}`);
  } catch {
    /* storage unavailable */
  }
}

export function Checkout({ goal }: { goal: PublicGoal }) {
  const router = useRouter();
  const fixedAmount = goal.targetType === "FIXED" ? goal.fixedAmountPesewas ?? null : null;
  const initialAmount = (fixedAmount ?? goal.minimumAmountPesewas) / 100;

  const [amount, setAmount] = useState(initialAmount.toFixed(2));
  const [displayName, setDisplayName] = useState("");
  const [anonymous, setAnonymous] = useState(false);
  const [momo, setMomo] = useState("");
  const [network, setNetwork] = useState<Network | "">("");
  const [quote, setQuote] = useState<FeeQuote | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const amountPesewas = parseGhsToPesewas(amount);
  const quoteMatches = quote !== null && quote.amountPesewas === amountPesewas;

  const quickAmounts = useMemo(() => {
    if (fixedAmount) {
      return [];
    }
    const minimumGhs = goal.minimumAmountPesewas / 100;
    const aboveMinimum = QUICK_AMOUNTS_GHS.filter((value) => value >= minimumGhs);
    return aboveMinimum.length > 0 ? aboveMinimum : [minimumGhs];
  }, [fixedAmount, goal.minimumAmountPesewas]);

  useEffect(() => {
    if (!amountPesewas) {
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const response = await fetch(`/api/goals/${goal.slug}/quote`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ amountPesewas }),
          signal: controller.signal,
        });
        if (!response.ok) {
          setQuote(null);
          setQuoteError(
            problemMessage((await response.json()) as Problem, {
              minimumAmountPesewas: goal.minimumAmountPesewas,
              fixedAmountPesewas: goal.fixedAmountPesewas,
            }),
          );
          return;
        }
        setQuote((await response.json()) as FeeQuote);
        setQuoteError(null);
      } catch {
        /* aborted, offline or timed out */
      }
    }, 250);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [
    amountPesewas,
    goal.slug,
    goal.minimumAmountPesewas,
    goal.fixedAmountPesewas,
  ]);

  function changeAmount(value: string) {
    const cleaned = value.replace(/[^0-9.]/g, "");
    const [whole, ...rest] = cleaned.split(".");
    const next = rest.length > 0 ? `${whole}.${rest.join("").slice(0, 2)}` : whole;
    setAmount(next);
  }

  function normalizeAmount() {
    const parsed = parseGhsToPesewas(amount);
    if (parsed) {
      setAmount((parsed / 100).toFixed(2));
    }
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    if (!amountPesewas) {
      setError("Enter a valid amount with at most two decimals.");
      return;
    }
    if (!quoteMatches) {
      setError("Wait for the total to update before paying.");
      return;
    }
    if (!/^0\d{9}$/.test(momo)) {
      setError("Enter a 10-digit mobile money number, for example 0240000001.");
      return;
    }
    if (!network) {
      setError("Choose the mobile money network.");
      return;
    }

    setSubmitting(true);
    const signature = JSON.stringify({
      amountPesewas,
      momo,
      network,
      displayName: anonymous ? null : displayName.trim(),
      anonymous,
    });
    try {
      const response = await fetch(`/api/goals/${goal.slug}/contributions`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": idempotencyKey(goal.slug, signature),
        },
        body: JSON.stringify({
          amountPesewas,
          momoNumber: momo,
          network,
          displayName: displayName.trim() || undefined,
          anonymous,
        }),
      });
      const data = (await response.json()) as ContributionStatus | Problem;
      if (!response.ok) {
        if (response.status < 500) {
          clearIdempotencyKey(goal.slug);
        }
        setError(
          problemMessage(data as Problem, {
            minimumAmountPesewas: goal.minimumAmountPesewas,
            fixedAmountPesewas: goal.fixedAmountPesewas,
          }),
        );
        return;
      }
      const status = data as ContributionStatus;
      if (status.state === "PENDING" || status.state === "CONFIRMED") {
        clearIdempotencyKey(goal.slug);
        router.push(`/c/${status.id}?goal=${encodeURIComponent(goal.slug)}`);
      } else if (status.state === "FAILED" || status.state === "EXPIRED") {
        clearIdempotencyKey(goal.slug);
        setError("The mobile money prompt could not be started. Try again.");
      } else {
        setError("Unexpected contribution state. Try again.");
      }
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      onSubmit={submit}
      className="mt-4 space-y-4 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card"
    >
      <div>
        <span className="block text-[11px] font-bold uppercase tracking-wider text-slate-500">
          Your contribution
        </span>
        {fixedAmount ? (
          <div className="mt-2 rounded-2xl bg-sage-100 px-4 py-3 font-mono text-lg font-black text-forest-800">
            {formatGhs(fixedAmount)}
          </div>
        ) : (
          <>
            <div className="mt-2 grid grid-cols-3 gap-2">
              {quickAmounts.map((value) => {
                const selected = amountPesewas === Math.round(value * 100);
                return (
                  <button
                    key={value}
                    type="button"
                    aria-pressed={selected}
                    onClick={() => setAmount(value.toFixed(2))}
                    className={`rounded-xl py-2 text-xs font-bold transition ${
                      selected
                        ? "bg-forest-600 text-white shadow-sm"
                        : "bg-slate-100 text-slate-700 hover:bg-slate-200"
                    }`}
                  >
                    {selected ? "✓ " : ""}GHS {value}
                  </button>
                );
              })}
            </div>
            <div className="relative mt-2 flex items-center">
              <span className="absolute left-3.5 text-xs font-bold text-forest-700">GHS</span>
              <input
                inputMode="decimal"
                value={amount}
                onChange={(event) => changeAmount(event.target.value)}
                onBlur={normalizeAmount}
                aria-label="Contribution amount in Ghana cedis"
                className={`${inputClass} pl-12 font-mono font-bold`}
              />
            </div>
          </>
        )}
      </div>

      <div className="flex items-center justify-between gap-3">
        <span className="text-xs font-medium text-slate-600">Contribute anonymously</span>
        <button
          type="button"
          role="switch"
          aria-checked={anonymous}
          aria-label="Contribute anonymously"
          onClick={() => setAnonymous((value) => !value)}
          className={`relative h-6 w-11 shrink-0 rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-forest-600 focus-visible:ring-offset-2 ${
            anonymous ? "bg-forest-600" : "bg-slate-300"
          }`}
        >
          <span
            className={`absolute top-1 h-4 w-4 rounded-full bg-white transition-all ${
              anonymous ? "right-1" : "left-1"
            }`}
          />
        </button>
      </div>
      {anonymous ? (
        <p className="text-[11px] text-slate-500">
          Your name is hidden on the public feed. The organizer can still see who gave.
        </p>
      ) : null}

      {!anonymous ? (
        <div>
          <label
            htmlFor="display-name"
            className="block text-[11px] font-bold uppercase tracking-wider text-slate-500"
          >
            Your name (optional)
          </label>
          <input
            id="display-name"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            maxLength={80}
            autoComplete="name"
            placeholder="Shown on the contribution feed"
            className={`${inputClass} mt-1.5`}
          />
        </div>
      ) : null}

      <div className="grid grid-cols-[1fr_auto] gap-2">
        <div>
          <label
            htmlFor="momo"
            className="block text-[11px] font-bold uppercase tracking-wider text-slate-500"
          >
            Mobile money number
          </label>
          <input
            id="momo"
            inputMode="numeric"
            autoComplete="tel"
            value={momo}
            onChange={(event) => setMomo(event.target.value.replace(/[^0-9]/g, "").slice(0, 10))}
            placeholder="0240000001"
            className={`${inputClass} mt-1.5 font-mono font-bold`}
          />
        </div>
        <div>
          <label
            htmlFor="network"
            className="block text-[11px] font-bold uppercase tracking-wider text-slate-500"
          >
            Network
          </label>
          <select
            id="network"
            value={network}
            onChange={(event) => setNetwork(event.target.value as Network | "")}
            className={`${inputClass} mt-1.5 w-auto font-semibold`}
          >
            <option value="" disabled>
              Select
            </option>
            {NETWORKS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {quoteMatches ? (
        <FeeBreakdown {...quote} />
      ) : (
        <p
          className={`rounded-2xl px-3.5 py-3 text-[11px] ${
            quoteError
              ? "border border-amber-200 bg-amber-50 font-semibold text-amber-800"
              : "bg-slate-50 text-slate-500"
          }`}
        >
          {quoteError ??
            (amountPesewas
              ? "Updating the exact total…"
              : "Enter an amount to see the exact total before you pay.")}
        </p>
      )}

      {error ? (
        <p
          role="alert"
          className="rounded-2xl border border-red-200 bg-red-50 px-3.5 py-2.5 text-xs font-semibold text-red-700"
        >
          {error}
        </p>
      ) : null}

      <button
        type="submit"
        disabled={submitting || !quoteMatches}
        className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 py-3 text-xs font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 disabled:cursor-not-allowed disabled:opacity-50"
      >
        <Bolt className="h-4 w-4" />
        {submitting
          ? "Starting payment…"
          : quoteMatches
            ? "Pay mobile money"
            : "Updating total…"}
      </button>
      <p className="text-center text-[10px] leading-relaxed text-slate-500">
        A 1.5% platform fee and MoMo processing are added at checkout. The goal receives the full
        contribution.
      </p>
    </form>
  );
}
