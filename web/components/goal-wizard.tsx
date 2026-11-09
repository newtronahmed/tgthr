"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { formatGhs } from "@/lib/money";
import { buildGoalPayload, type GoalFormState } from "@/lib/organizer-form";
import { organizerProblemMessage } from "@/lib/problems";
import type { Problem } from "@/lib/types";
import { ArrowRight, Shield } from "./icons";

const TEMPLATES: { value: GoalFormState["template"]; label: string }[] = [
  { value: "TRIP", label: "Trip" },
  { value: "WEDDING", label: "Wedding" },
  { value: "FUNERAL", label: "Funeral" },
  { value: "RENT", label: "Rent" },
  { value: "COMMUNITY", label: "Church" },
  { value: "GIFT", label: "Gift" },
  { value: "PARTY", label: "Party" },
];

const inputClass =
  "w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-base text-slate-900 outline-none transition focus:border-forest-600 focus:ring-2 focus:ring-forest-600/15 sm:text-sm";
const labelClass =
  "mb-1 block text-[10px] font-bold uppercase tracking-[0.12em] text-slate-500";

function defaultDeadline(): string {
  return new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
}

function initialForm(): GoalFormState {
  return {
    template: "FUNERAL",
    title: "",
    targetType: "FIXED",
    fixedAmount: "50.00",
    minimumAmount: "10.00",
    targetAmount: "",
    deadline: defaultDeadline(),
    destinationType: "BENEFICIARY_MOMO",
    destinationPhone: "",
    network: "MTN",
    secondApprover: "OFF",
    secondApproverName: "",
    secondApproverPhone: "",
    threshold: "500.00",
    holdWindow: "60",
    surplusRule: "PAY_BENEFICIARY",
  };
}

export function GoalWizard() {
  const router = useRouter();
  const [form, setForm] = useState<GoalFormState>(initialForm);
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set<K extends keyof GoalFormState>(key: K, value: GoalFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const result = buildGoalPayload(form);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setBusy(true);
    try {
      const response = await fetch("/api/organizer/goals", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": idempotencyKey,
        },
        body: JSON.stringify(result.payload),
      });
      if (response.status === 401) {
        router.push("/signin");
        return;
      }
      const body = (await response.json().catch(() => ({}))) as Problem & { id?: string };
      if (!response.ok || !body.id) {
        setError(organizerProblemMessage(body));
        return;
      }
      router.push(`/organizer/goals/${body.id}`);
      router.refresh();
    } catch {
      setError("Could not reach tgthr. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  const preview = previewLines(form);

  return (
    <form onSubmit={submit} className="space-y-4">
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-forest-700">
          tgthr goal wizard
        </span>
        <h1 className="mt-1 text-2xl font-black text-slate-900">Create a goal</h1>
        <p className="mt-1 text-xs leading-relaxed text-slate-500">
          Contributors see the target and payout safeguards before they pay.
        </p>

        <fieldset className="mt-4">
          <legend className={labelClass}>Template</legend>
          <div className="grid grid-cols-4 gap-2">
            {TEMPLATES.map((template) => (
              <button
                key={template.value}
                type="button"
                aria-pressed={form.template === template.value}
                onClick={() => set("template", template.value)}
                className={`rounded-2xl border px-2 py-2 text-[10px] font-bold transition ${
                  form.template === template.value
                    ? "border-forest-800 bg-forest-800 text-white"
                    : "border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
                }`}
              >
                {form.template === template.value ? "✓ " : ""}
                {template.label}
              </button>
            ))}
          </div>
        </fieldset>

        <div className="mt-4">
          <label htmlFor="title" className={labelClass}>
            Goal title
          </label>
          <input
            id="title"
            required
            minLength={3}
            maxLength={120}
            value={form.title}
            onChange={(event) => set("title", event.target.value)}
            placeholder="Cape Coast reunion trip"
            className={inputClass}
          />
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2">
          <div>
            <label htmlFor="targetType" className={labelClass}>
              Target type
            </label>
            <select
              id="targetType"
              value={form.targetType}
              onChange={(event) =>
                set("targetType", event.target.value as GoalFormState["targetType"])
              }
              className={inputClass}
            >
              <option value="FIXED">Fixed target</option>
              <option value="OPEN">Open target</option>
            </select>
          </div>
          <div>
            <label htmlFor="deadline" className={labelClass}>
              Deadline
            </label>
            <input
              id="deadline"
              type="date"
              required
              value={form.deadline}
              onChange={(event) => set("deadline", event.target.value)}
              className={inputClass}
            />
          </div>
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2">
          {form.targetType === "FIXED" ? (
            <div>
              <label htmlFor="fixedAmount" className={labelClass}>
                Fixed amount (GHS)
              </label>
              <input
                id="fixedAmount"
                inputMode="decimal"
                value={form.fixedAmount}
                onChange={(event) => set("fixedAmount", event.target.value)}
                className={`${inputClass} font-mono font-bold`}
              />
            </div>
          ) : (
            <div>
              <label htmlFor="minimumAmount" className={labelClass}>
                Minimum (GHS)
              </label>
              <input
                id="minimumAmount"
                inputMode="decimal"
                value={form.minimumAmount}
                onChange={(event) => set("minimumAmount", event.target.value)}
                className={`${inputClass} font-mono font-bold`}
              />
            </div>
          )}
          <div>
            <label htmlFor="targetAmount" className={labelClass}>
              Target (optional)
            </label>
            <input
              id="targetAmount"
              inputMode="decimal"
              value={form.targetAmount}
              onChange={(event) => set("targetAmount", event.target.value)}
              placeholder="5000"
              className={`${inputClass} font-mono`}
            />
          </div>
        </div>
      </section>

      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft-card">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4 text-forest-600" />
          <h2 className="text-xs font-extrabold uppercase tracking-[0.12em] text-slate-500">
            Payout protection
          </h2>
        </div>

        <div className="mt-3">
          <label htmlFor="destinationType" className={labelClass}>
            Destination
          </label>
          <select
            id="destinationType"
            value={form.destinationType}
            onChange={(event) =>
              set("destinationType", event.target.value as GoalFormState["destinationType"])
            }
            className={inputClass}
          >
            <option value="BENEFICIARY_MOMO">Beneficiary MoMo</option>
            <option value="ADMIN_MOMO">My own MoMo</option>
          </select>
          <p className="mt-1 text-[10px] leading-relaxed text-slate-500">
            {form.destinationType === "ADMIN_MOMO"
              ? "Uses your sign-in legal name and must be your own number."
              : "The account name is checked with the network before the goal is created."}
          </p>
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2">
          <div>
            <label htmlFor="destinationPhone" className={labelClass}>
              Payout number
            </label>
            <input
              id="destinationPhone"
              inputMode="tel"
              required
              value={form.destinationPhone}
              onChange={(event) => set("destinationPhone", event.target.value)}
              placeholder="024 000 0000"
              className={`${inputClass} font-mono`}
            />
          </div>
          <div>
            <label htmlFor="network" className={labelClass}>
              Network
            </label>
            <select
              id="network"
              value={form.network}
              onChange={(event) => set("network", event.target.value as GoalFormState["network"])}
              className={inputClass}
            >
              <option value="MTN">MTN</option>
              <option value="TELECEL">Telecel</option>
              <option value="AT">AT</option>
            </select>
          </div>
        </div>

        <div className="mt-3 grid grid-cols-2 gap-2">
          <div>
            <label htmlFor="secondApprover" className={labelClass}>
              Second approver
            </label>
            <select
              id="secondApprover"
              value={form.secondApprover}
              onChange={(event) =>
                set("secondApprover", event.target.value as GoalFormState["secondApprover"])
              }
              className={inputClass}
            >
              <option value="OFF">Off</option>
              <option value="ABOVE_THRESHOLD">Above a threshold</option>
              <option value="ALWAYS">Always</option>
            </select>
          </div>
          {form.secondApprover === "ABOVE_THRESHOLD" ? (
            <div>
              <label htmlFor="threshold" className={labelClass}>
                Approver threshold (GHS)
              </label>
              <input
                id="threshold"
                inputMode="decimal"
                value={form.threshold}
                onChange={(event) => set("threshold", event.target.value)}
                className={`${inputClass} font-mono`}
              />
            </div>
          ) : null}
        </div>

        {form.secondApprover !== "OFF" ? (
          <div className="mt-3 grid grid-cols-2 gap-2">
            <div>
              <label htmlFor="secondApproverName" className={labelClass}>
                Approver name
              </label>
              <input
                id="secondApproverName"
                value={form.secondApproverName}
                onChange={(event) => set("secondApproverName", event.target.value)}
                className={inputClass}
                maxLength={120}
              />
            </div>
            <div>
              <label htmlFor="secondApproverPhone" className={labelClass}>
                Approver MoMo
              </label>
              <input
                id="secondApproverPhone"
                inputMode="tel"
                value={form.secondApproverPhone}
                onChange={(event) => set("secondApproverPhone", event.target.value)}
                className={`${inputClass} font-mono`}
                placeholder="024 000 0000"
              />
            </div>
          </div>
        ) : null}

        <div className="mt-3 grid grid-cols-2 gap-2">
          <div>
            <label htmlFor="holdWindow" className={labelClass}>
              Hold window (minutes)
            </label>
            <input
              id="holdWindow"
              type="number"
              min={1}
              max={1440}
              value={form.holdWindow}
              onChange={(event) => set("holdWindow", event.target.value)}
              className={`${inputClass} font-mono`}
            />
          </div>
          <div>
            <label htmlFor="surplusRule" className={labelClass}>
              Surplus rule
            </label>
            <select
              id="surplusRule"
              value={form.surplusRule}
              onChange={(event) =>
                set("surplusRule", event.target.value as GoalFormState["surplusRule"])
              }
              className={inputClass}
            >
              <option value="PAY_BENEFICIARY">Pay beneficiary</option>
              <option value="WELFARE_RESERVE">Welfare reserve</option>
            </select>
          </div>
        </div>
      </section>

      <section className="rounded-3xl border border-sage-200 bg-sage-100 p-5">
        <h2 className="text-[10px] font-bold uppercase tracking-[0.14em] text-forest-700">
          Review
        </h2>
        <dl className="mt-2 space-y-1.5 text-xs">
          {preview.map((line) => (
            <div key={line.label} className="flex justify-between gap-4">
              <dt className="text-slate-500">{line.label}</dt>
              <dd className="text-right font-semibold text-slate-900">{line.value}</dd>
            </div>
          ))}
        </dl>
      </section>

      {error ? (
        <p role="alert" className="rounded-xl bg-amber-50 px-3 py-2 text-xs font-semibold text-amber-800">
          {error}
        </p>
      ) : null}

      <button
        type="submit"
        disabled={busy}
        className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-forest-600 px-5 py-3 text-sm font-extrabold text-white shadow-floating-btn transition hover:bg-forest-700 disabled:opacity-60"
      >
        {busy ? "Creating…" : "Create goal"}
        <ArrowRight className="h-4 w-4" />
      </button>
    </form>
  );
}

function previewLines(form: GoalFormState): { label: string; value: string }[] {
  const result = buildGoalPayload(form);
  const amount = result.ok
    ? result.payload.targetType === "FIXED"
      ? `${formatGhs(result.payload.fixedAmountPesewas ?? 0)} each`
      : `From ${formatGhs(result.payload.minimumAmountPesewas ?? 0)}`
    : "—";
  return [
    { label: "Template", value: TEMPLATES.find((item) => item.value === form.template)?.label ?? "—" },
    { label: "Amount", value: amount },
    { label: "Deadline", value: form.deadline || "—" },
    { label: "Destination", value: form.destinationPhone || "—" },
    { label: "Hold window", value: `${form.holdWindow || "0"} minutes` },
  ];
}
