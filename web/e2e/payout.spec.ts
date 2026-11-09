import { expect, test } from "@playwright/test";
import { criterion } from "../lib/criterion";

criterion("AC-PAYOUT-012");

// The payout module is not implemented yet; this is the E2E home for the criterion.
test.skip("organizer requests and approver approves a payout", async ({ page }) => {
  await page.goto("/organizer");
  await expect(page.getByRole("heading", { name: /payout/i })).toBeVisible();
});
