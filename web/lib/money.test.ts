import assert from "node:assert/strict";
import { test } from "node:test";
import { criterion } from "./criterion.ts";
import { formatGhs, parseGhsToPesewas } from "./money.ts";

criterion("AC-CONTRIB-022");

test("formats integer pesewas without float arithmetic", () => {
  assert.equal(formatGhs(1_000), "GHS 10.00");
  assert.equal(formatGhs(1_036), "GHS 10.36");
  assert.equal(formatGhs(10_000_000), "GHS 100,000.00");
});

test("parses whole and two-decimal amounts", () => {
  assert.equal(parseGhsToPesewas("10"), 1_000);
  assert.equal(parseGhsToPesewas("10.5"), 1_050);
  assert.equal(parseGhsToPesewas("10.05"), 1_005);
});

test("rejects malformed amounts instead of silently truncating", () => {
  for (const value of ["", "-100", "1e3", "10,50", "10.999", ".", "0", "0.00"]) {
    assert.equal(parseGhsToPesewas(value), null, value);
  }
});
