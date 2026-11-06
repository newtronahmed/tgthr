export function formatGhs(pesewas: number): string {
  const sign = pesewas < 0 ? "-" : "";
  const absolute = Math.abs(Math.trunc(pesewas));
  const cedis = Math.floor(absolute / 100);
  const minor = absolute % 100;
  return `GHS ${sign}${cedis.toLocaleString("en-GH")}.${String(minor).padStart(2, "0")}`;
}

export function parseGhsToPesewas(input: string): number | null {
  const trimmed = input.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(trimmed)) {
    return null;
  }
  const [whole, fraction = ""] = trimmed.split(".");
  const pesewas = Number(whole) * 100 + Number(`${fraction}00`.slice(0, 2));
  return Number.isSafeInteger(pesewas) && pesewas > 0 ? pesewas : null;
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("en-GH", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
