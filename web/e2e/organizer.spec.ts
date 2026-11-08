import { expect, test } from "@playwright/test";
import { criterion } from "../lib/criterion";

criterion("AC-ORG-001", "AC-ORG-002", "AC-ORG-005", "AC-ORG-013");

const BACKEND = process.env.BACKEND_URL ?? "http://127.0.0.1:8090/v1";

test("organizer signs in, creates a goal and closes it", async ({ page, request }) => {
  const phone = `024${String(Date.now()).slice(-7)}`;
  await page.goto("/signin");
  await page.getByLabel("Mobile money number").fill(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await expect(page.getByLabel("Six-digit code")).toBeVisible();

  const codeResponse = await request.get(
    `${BACKEND}/dev/otp/last?phone=${encodeURIComponent(phone)}`,
  );
  expect(codeResponse.ok()).toBeTruthy();
  const { code } = (await codeResponse.json()) as { code: string };

  await page.getByLabel("Six-digit code").fill(code);
  await page.getByLabel("Display name").fill("Ama");
  await page.getByLabel("Legal name (optional)").fill("Ama Mensah");
  await page.getByRole("button", { name: "Verify and continue" }).click();

  await expect(page).toHaveURL(/\/organizer$/);
  await expect(page.getByRole("heading", { name: "Hey Ama" })).toBeVisible();
  const session = (await page.context().cookies()).find(
    (cookie) => cookie.name === "tgthr_session",
  );
  expect(session?.httpOnly).toBeTruthy();
  expect(session?.sameSite).toBe("Lax");
  await page.getByRole("link", { name: "Create a goal" }).click();

  await page.getByLabel("Goal title").fill("E2E Organizer Goal");
  await page.getByLabel("Payout number").fill("0240000202");
  await page.getByRole("button", { name: "Create goal" }).click();

  await expect(page).toHaveURL(/\/organizer\/goals\/[0-9a-f-]+$/);
  await expect(page.getByRole("heading", { name: "E2E Organizer Goal" })).toBeVisible();
  await expect(page.getByTestId("goal-status")).toHaveText("OPEN");
  await expect(page.getByText("Kofi Mensah")).toBeVisible();

  await page.goto("/organizer");
  await expect(page.getByRole("link", { name: /E2E Organizer Goal/ })).toBeVisible();
  await page.getByRole("link", { name: /E2E Organizer Goal/ }).click();
  await expect(page.getByTestId("goal-status")).toHaveText("OPEN");
  const detailUrl = page.url();

  await page.getByRole("link", { name: "View public page" }).click();
  await expect(page.getByRole("heading", { name: "E2E Organizer Goal" })).toBeVisible();
  await page.goto(detailUrl);

  await expect(page.getByTestId("goal-status")).toHaveText("OPEN");
  await page.getByRole("button", { name: "Close goal" }).click();
  await page.getByRole("button", { name: "Confirm close" }).click();
  await expect(page.getByTestId("goal-status")).toHaveText("CLOSED");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/organizer");
  await expect(page).toHaveURL(/\/signin$/);
});
