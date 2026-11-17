import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { totp, waitForNextStep } from "./operator-totp";

const STATE = process.env.OPERATOR_E2E_STATE ?? "/tmp/tgthr-operator-e2e.json";
const TOKEN_FILE = process.env.OPERATOR_BOOTSTRAP_TOKEN_FILE ?? "/tmp/operator-token";
const PASSWORD = "correct horse battery staple";
const REFUND_CONTRIBUTION = "d0000000-0000-0000-0000-000000000001";
const SUSPENSE_GOAL = "e1000000-0000-0000-0000-000000000001";
const SUSPENSE_REFERENCE = "e2e-suspense-contribution";

type OperatorState = {
  secret: string;
  recovery: string[];
  refunded?: boolean;
  resolved?: boolean;
};

function readState(): OperatorState | null {
  if (!existsSync(STATE)) {
    return null;
  }
  return JSON.parse(readFileSync(STATE, "utf8")) as OperatorState;
}

function writeState(state: OperatorState): void {
  writeFileSync(STATE, JSON.stringify(state), { mode: 0o600 });
}

function updateState(patch: Partial<OperatorState>): void {
  const current = readState();
  if (current) {
    writeState({ ...current, ...patch });
  }
}

async function enrollIfNeeded(page: import("@playwright/test").Page): Promise<string> {
  const existing = readState();
  if (existing) {
    return existing.secret;
  }
  const tokenLine = readFileSync(TOKEN_FILE, "utf8").trim();
  const token = tokenLine.startsWith("OPERATOR_BOOTSTRAP_ENROLLMENT_TOKEN=")
    ? tokenLine.split("=").slice(1).join("=")
    : tokenLine;

  await page.goto("/operator/enroll");
  await page.fill("#enrollment-token", token);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/\/operator\/enroll$/);
  await page.fill("#enroll-password", PASSWORD);
  await page.fill("#enroll-password-confirm", PASSWORD);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("Authenticator secret")).toBeVisible({ timeout: 30_000 });

  const secret = (await page.getByTestId("operator-totp-secret").textContent())?.trim();
  const recovery = await page.locator("ul.grid li").allTextContents();
  if (!secret || recovery.length === 0) {
    throw new Error("enrollment did not return a TOTP secret and recovery codes");
  }
  await page.fill("#enroll-code", totp(secret));
  await page.getByRole("button", { name: "Finish enrollment" }).click();
  await expect(page).toHaveURL(/\/operator\/signin$/, { timeout: 30_000 });
  writeState({ secret, recovery });
  return secret;
}

test("operator signs in, refunds a contribution and resolves suspense", async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000);
  test.skip(testInfo.project.name !== "chromium", "operator flow runs once");
  const secret = await enrollIfNeeded(page);

  await page.goto("/operator/signin");
  await page.fill("#operator-username", "e2e.admin");
  await page.fill("#operator-password", PASSWORD);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/\/operator\/signin\?step=mfa/, { timeout: 30_000 });
  await waitForNextStep();
  await page.fill("#operator-code", totp(secret));
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/operator$/, { timeout: 30_000 });
  await expect(page.getByRole("heading", { name: "Refunds" })).toBeVisible();

  if (readState()?.refunded) {
    await expect(page.getByText("SUCCEEDED").first()).toBeVisible({ timeout: 15_000 });
  } else {
    await page.fill('input[name="contributionId"]', REFUND_CONTRIBUTION);
    await page.fill('input[name="reason"]', "E2E duplicate charge");
    await page.getByRole("button", { name: "Request refund" }).click();
    await page.waitForURL(/\/operator\?(ok|error)=/, { timeout: 30_000 });
    const error = new URL(page.url()).searchParams.get("error");
    if (error) {
      // Only an already-terminal refund may satisfy the rerun path.
      expect(error).toBe("REFUND_NOT_ELIGIBLE");
      await expect(page.getByText("SUCCEEDED").first()).toBeVisible({ timeout: 15_000 });
    } else {
      await expect(page.getByText(/Refund requested/)).toBeVisible();
      await expect(page.getByText("SUCCEEDED").first()).toBeVisible({ timeout: 15_000 });
    }
    updateState({ refunded: true });
  }

  await page.goto("/operator/reconciliation");
  await expect(page.getByRole("heading", { name: "Reconciliation" })).toBeVisible();

  if (readState()?.resolved) {
    // This flag is written only after a prior run saw the card and the empty queue.
    await expect(page.getByText("Queue is clear")).toBeVisible();
  } else {
    await expect(page.getByText("E2E Suspense")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("GHS 50.00").first()).toBeVisible();
    await expect(page.getByText(SUSPENSE_GOAL)).toBeVisible();
    await expect(page.getByText(SUSPENSE_REFERENCE)).toBeVisible();
    await expect(page.getByText(/age \d/)).toBeVisible();
    const queueText = await page.locator("main").innerText();
    expect(queueText).not.toContain("E2E Suspense Payer");
    expect(queueText).not.toContain("0240000");
    expect(queueText).not.toContain("phone");

    await page.getByText("Reclassify to goal").click();
    await page
      .locator('details input[name="reason"]')
      .first()
      .fill("E2E late payment belongs to the goal");
    await page.getByRole("button", { name: "Reclassify", exact: true }).click();
    await expect(page).toHaveURL(/ok=reclassified/, { timeout: 30_000 });
    await expect(page.getByText("Queue is clear")).toBeVisible();
    updateState({ resolved: true });
  }

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/operator\/signin$/, { timeout: 30_000 });
});
