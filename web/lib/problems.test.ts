import assert from "node:assert/strict";
import { test } from "node:test";
import { criterion } from "./criterion.ts";
import { problemMessage } from "./problems.ts";

criterion("AC-CONTRIB-022", "AC-CONTRIB-012", "AC-CONTRIB-019", "AC-CONTRIB-015");

test("maps amount problems to the goal rules", () => {
  assert.equal(
    problemMessage({ code: "AMOUNT_TOO_LOW" }, { minimumAmountPesewas: 1_000 }),
    "The minimum contribution is GHS 10.00.",
  );
  assert.equal(
    problemMessage(
      { code: "AMOUNT_NOT_ALLOWED" },
      { minimumAmountPesewas: 1_000, fixedAmountPesewas: 5_000 },
    ),
    "This goal accepts exactly GHS 50.00.",
  );
});

test("maps acceptance and rate-limit problems", () => {
  assert.equal(
    problemMessage({ code: "GOAL_NOT_ACCEPTING" }, { minimumAmountPesewas: 1_000 }),
    "This goal is no longer accepting contributions.",
  );
  assert.equal(
    problemMessage({ code: "RATE_LIMITED" }, { minimumAmountPesewas: 1_000 }),
    "Too many attempts. Wait a minute and try again.",
  );
});

test("falls back to the problem detail", () => {
  assert.equal(
    problemMessage({ code: "UNKNOWN", detail: "Server exploded" }, { minimumAmountPesewas: 1_000 }),
    "Server exploded",
  );
});
