"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import type { ContributionStatus } from "@/lib/types";

export function StatusPoller({ id }: { id: string }) {
  const router = useRouter();

  useEffect(() => {
    let attempts = 0;
    const timer = setInterval(async () => {
      attempts += 1;
      if (attempts > 30) {
        clearInterval(timer);
        router.refresh();
        return;
      }
      try {
        const response = await fetch(`/api/contributions/${id}`);
        if (!response.ok) {
          return;
        }
        const status = (await response.json()) as ContributionStatus;
        if (
          status.state === "CONFIRMED" ||
          status.state === "FAILED" ||
          status.state === "EXPIRED"
        ) {
          clearInterval(timer);
          router.refresh();
        }
      } catch {
        /* keep polling */
      }
    }, 2_000);
    return () => clearInterval(timer);
  }, [id, router]);

  return null;
}
