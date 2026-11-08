import Link from "next/link";
import { BrandMark, Wordmark } from "./brand";
import { LogoutButton } from "./logout-button";
import type { OrganizerProfile } from "@/lib/types";

export function OrganizerNav({ profile }: { profile?: OrganizerProfile }) {
  return (
    <header className="flex items-center justify-between gap-3">
      <Link href="/" className="flex items-center gap-2">
        <BrandMark size={32} />
        <Wordmark />
      </Link>
      <nav className="flex items-center gap-3 text-xs font-bold text-slate-600">
        <Link href="/organizer" className="transition hover:text-forest-700">
          Dashboard
        </Link>
        <Link href="/organizer/goals/new" className="transition hover:text-forest-700">
          New goal
        </Link>
        {profile?.displayName ? (
          <span className="hidden text-slate-400 sm:inline">{profile.displayName}</span>
        ) : null}
        <LogoutButton />
      </nav>
    </header>
  );
}
