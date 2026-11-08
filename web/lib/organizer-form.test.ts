import assert from "node:assert/strict";
import { test } from "node:test";
import { buildGoalPayload, type GoalFormState } from "./organizer-form.ts";

const base: GoalFormState = {
  template: "FUNERAL",
  title: "Ama's funeral",
  targetType: "FIXED",
  fixedAmount: "50.00",
  minimumAmount: "10.00",
  targetAmount: "",
  deadline: "2030-01-01",
  destinationType: "BENEFICIARY_MOMO",
  destinationPhone: "0240000002",
  network: "MTN",
  secondApprover: "OFF",
  threshold: "500.00",
  holdWindow: "60",
  surplusRule: "PAY_BENEFICIARY",
};

test("builds a fixed goal with integer pesewas", () => {
  const result = buildGoalPayload(base, new Date("2026-01-01T00:00:00Z"));
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.payload.fixedAmountPesewas, 5_000);
    assert.equal(result.payload.secondApproverThresholdPesewas, 50_000);
    assert.equal(result.payload.destinationPhone, "0240000002");
  }
});

test("normalizes an E.164 payout number", () => {
  const result = buildGoalPayload(
    { ...base, destinationPhone: "+233240000002" },
    new Date("2026-01-01T00:00:00Z"),
  );
  assert.equal(result.ok, true);
  if (result.ok) {
    assert.equal(result.payload.destinationPhone, "0240000002");
  }
});

test("open goals require a minimum of 1,000 pesewas", () => {
  const result = buildGoalPayload(
    { ...base, targetType: "OPEN", minimumAmount: "9.99" },
    new Date("2026-01-01T00:00:00Z"),
  );
  assert.equal(result.ok, false);
});

test("rejects past deadlines and malformed phones", () => {
  assert.equal(
    buildGoalPayload({ ...base, deadline: "2020-01-01" }, new Date("2026-01-01T00:00:00Z")).ok,
    false,
  );
  assert.equal(buildGoalPayload({ ...base, destinationPhone: "123" }).ok, false);
});
