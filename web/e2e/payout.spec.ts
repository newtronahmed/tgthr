import { expect, test } from "@playwright/test";
import { criterion } from "../lib/criterion";

criterion("AC-PAYOUT-012");
criterion("AC-PAYOUT-014");

const BACKEND = process.env.BACKEND_URL ?? "http://127.0.0.1:8090/v1";

test("organizer requests and approver approves a payout", async ({ page, request }) => {
  const phone = `024${String(Date.now()).slice(-7)}`;
  const approverPhone = "0240000203";
  await page.goto("/signin");
  await page.getByLabel("Mobile money number").fill(phone);
  await page.getByRole("button", { name: "Send code" }).click();
  await expect(page.getByLabel("Six-digit code")).toBeVisible();
  const otpResponse = await request.get(`${BACKEND}/dev/otp/last?phone=${encodeURIComponent(phone)}`);
  expect(otpResponse.ok()).toBeTruthy();
  const { code: otp } = (await otpResponse.json()) as { code: string };
  await page.getByLabel("Six-digit code").fill(otp);
  await page.getByLabel("Display name").fill("Ama");
  await page.getByLabel("Legal name (optional)").fill("Ama Mensah");
  await page.getByRole("button", { name: "Verify and continue" }).click();
  await expect(page).toHaveURL(/\/organizer$/);

  await page.goto("/organizer/goals/new");
  await page.getByLabel("Goal title").fill("E2E Payout Goal");
  await page.getByLabel("Payout number").fill("0240000202");
  await page.getByLabel("Second approver").selectOption("ALWAYS");
  await page.getByLabel("Approver name").fill("Esi Mensah");
  await page.getByLabel("Approver MoMo").fill(approverPhone);
  await page.getByRole("button", { name: "Create goal" }).click();
  await expect(page).toHaveURL(/\/organizer\/goals\/[0-9a-f-]+$/);
  const goalId = new URL(page.url()).pathname.split("/").pop()!;

  const funded = await request.post(`${BACKEND}/dev/goals/${goalId}/settle`);
  expect(funded.ok()).toBeTruthy();
  await page.reload();
  await page.getByLabel("Payout amount (GHS)").fill("10.00");
  await page.getByRole("button", { name: "Request payout" }).click();
  await expect(page.getByText(/Awaiting the designated approver/)).toBeVisible();

  const approvalResponse = await request.get(
    `${BACKEND}/dev/payout-approvals/last?phone=${encodeURIComponent(approverPhone)}`,
  );
  expect(approvalResponse.ok()).toBeTruthy();
  const approval = (await approvalResponse.json()) as { token: string; code: string };
  await page.goto(`/approve/${approval.token}`);
  await expect(page.getByRole("heading", { name: "E2E Payout Goal" })).toBeVisible();
  await page.getByLabel("Six-digit code").fill(approval.code);
  await page.getByRole("button", { name: "Approve payout" }).click();
  await expect(page.getByText(/Approved. The payout/).first()).toBeVisible();

  await page.goto(`/organizer/goals/${goalId}`);
  await expect(page.getByText(/hold/i).first()).toBeVisible();
  await expect(page.locator("body")).not.toContainText(approverPhone);
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
