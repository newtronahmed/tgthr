import Link from "next/link";
import type { OperatorProfile } from "@/lib/operator-types";
import { logout } from "@/app/operator/auth-actions";
import { BrandMark, Wordmark } from "./brand";

export function OperatorNav({ profile }: { profile: OperatorProfile }) {
  return (
    <header className="flex items-center justify-between gap-3">
      <Link href="/operator" className="flex items-center gap-2">
        <BrandMark size={32} />
        <Wordmark />
      </Link>
      <nav className="flex items-center gap-3 text-xs font-bold text-slate-600">
        <Link href="/operator" className="transition hover:text-forest-700">
          Refunds
        </Link>
        <Link href="/operator/reconciliation" className="transition hover:text-forest-700">
          Reconciliation
        </Link>
        <span className="hidden text-slate-400 sm:inline">{profile.username}</span>
        <form action={logout}>
          <button
            type="submit"
            className="text-xs font-bold text-slate-500 transition hover:text-forest-700"
          >
            Sign out
          </button>
        </form>
      </nav>
    </header>
  );
}
