import assert from "node:assert/strict";
import test from "node:test";
import { clientIp } from "./client-ip.ts";

function request(headers: Record<string, string>): Request {
  return new Request("https://tgthr.example/api/goals/x/quote", { method: "POST", headers });
}

test("prefers the platform-provided x-real-ip over a client-supplied x-forwarded-for", () => {
  // A caller can set any x-forwarded-for they like; only the platform edge sets x-real-ip.
  const forged = request({
    "x-forwarded-for": "1.2.3.4",
    "x-real-ip": "203.0.113.9",
  });

  assert.equal(clientIp(forged), "203.0.113.9");
});

test("does not fall back to a forged x-forwarded-for when x-real-ip is absent", () => {
  // Without the platform header there is no trustworthy address at all. Returning the
  // client-supplied value would let one caller rotate their rate-limit bucket at will.
  const forged = request({ "x-forwarded-for": "1.2.3.4" });

  assert.equal(clientIp(forged), undefined);
});

test("returns x-real-ip when it is the only header present", () => {
  assert.equal(clientIp(request({ "x-real-ip": "203.0.113.9" })), "203.0.113.9");
});

test("returns undefined when no address header is present", () => {
  assert.equal(clientIp(request({})), undefined);
});

test("trims surrounding whitespace from the platform header", () => {
  assert.equal(clientIp(request({ "x-real-ip": "  203.0.113.9  " })), "203.0.113.9");
});
