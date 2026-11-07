import { expect, test } from "@playwright/test";
import { criterion } from "../lib/criterion";

criterion("AC-CONTRIB-001", "AC-CONTRIB-005", "AC-CONTRIB-013", "AC-CONTRIB-022");

const FAKE = process.env.PAYSTACK_FAKE_URL ?? "http://127.0.0.1:9099";

test("guest pays by mobile money and sees the receipt", async ({ page, request }) => {
  await page.goto("/g/e2e-maldives");
  await expect(page.getByRole("heading", { name: "E2E Maldives" })).toBeVisible();

  await page.getByRole("button", { name: /GHS 100/ }).click();
  await page.getByLabel("Your name (optional)").fill("E2E Guest");
  await page.getByLabel("Mobile money number").fill("0240000001");
  await page.getByLabel("Network").selectOption("MTN");
  await expect(page.getByText("Total charged")).toBeVisible();

  const pay = page.getByRole("button", { name: "Pay mobile money" });
  await expect(pay).toBeEnabled();
  await pay.click();

  await expect(page).toHaveURL(/\/c\/[0-9a-f-]+/);
  await expect(page.locator("main").getByRole("heading", { name: "Check your phone" })).toBeVisible();
  const contributionId = page.url().match(/\/c\/([0-9a-f-]+)/)?.[1];
  expect(contributionId).toBeTruthy();

  const callback = await request.post(`${FAKE}/simulate`, {
    data: { reference: contributionId, outcome: "success" },
  });
  expect(callback.ok()).toBeTruthy();

  await expect(page.locator("main").getByText("Transaction confirmed")).toBeVisible({ timeout: 20_000 });
  await expect(page.locator("main").getByText("POSTED")).toBeVisible();
  await expect(page.locator("main").getByText(`Ref: ${contributionId}`)).toBeVisible();
});
