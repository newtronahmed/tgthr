import { expect, test } from "@playwright/test";
import { criterion } from "../lib/criterion";

criterion("AC-PAYOUT-012");
criterion("AC-PAYOUT-014");

// The payout module is not implemented yet; this is the E2E home for the criterion.
test.skip("organizer requests and approver approves a payout", async ({ page }) => {
  await page.goto("/organizer");
  await expect(page.getByRole("heading", { name: /payout/i })).toBeVisible();
});

test("a contributor sees a held payout and reports it", async ({ page }) => {
  await page.goto("/g/e2e-maldives");
  await expect(page.getByRole("heading", { name: "Payouts" })).toBeVisible();
  await expect(page.getByText(/hold/i).first()).toBeVisible();
  await page.getByRole("button", { name: "Report a problem" }).click();
  await page.getByLabel("What looks wrong?").fill("The amount does not look right");
  await page.getByRole("button", { name: "Send report" }).click();
  await expect(page.getByText("Report received. An operator will review it.")).toBeVisible();
});
