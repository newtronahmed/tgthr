import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { GoalWizard } from "@/components/goal-wizard";
import { OrganizerNav } from "@/components/organizer-nav";
import { getOrganizerProfile } from "@/lib/organizer";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Create a goal",
  robots: { index: false, follow: false },
};

export default async function NewGoalPage() {
  const profile = await getOrganizerProfile();
  if (!profile) {
    redirect("/signin");
  }

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-lg flex-col px-5 py-6">
      <OrganizerNav profile={profile} />
      <div className="mt-6">
        <GoalWizard />
      </div>
    </main>
  );
}
