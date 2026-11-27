import assert from "node:assert/strict";
import test from "node:test";
import nextConfig from "../next.config.ts";

type Header = { key: string; value: string };

async function headers(): Promise<Map<string, string>> {
  assert.ok(nextConfig.headers, "next.config.ts must declare headers()");
  const groups = await nextConfig.headers();
  const all = groups.flatMap((group) => group.headers as Header[]);
  return new Map(all.map((header) => [header.key.toLowerCase(), header.value]));
}

test("every security header the runbook promises is present", async () => {
  const configured = await headers();

  for (const key of [
    "x-frame-options",
    "x-content-type-options",
    "referrer-policy",
    "strict-transport-security",
    "permissions-policy",
    "content-security-policy",
  ]) {
    assert.ok(configured.has(key), `missing ${key}`);
  }

  assert.equal(configured.get("x-frame-options"), "DENY");
  assert.equal(configured.get("x-content-type-options"), "nosniff");
  assert.match(configured.get("strict-transport-security")!, /max-age=\d{8,}/);
});

test("the CSP does not allow eval", async () => {
  const csp = (await headers()).get("content-security-policy")!;

  // 'unsafe-eval' turns any HTML-injection foothold into script execution. Nothing in this app
  // needs it: no eval, no new Function, and no third-party script is loaded.
  assert.doesNotMatch(csp, /unsafe-eval/);
});

test("the CSP pins the dangerous directives", async () => {
  const csp = (await headers()).get("content-security-policy")!;

  // Clickjacking and base-tag hijacking are cheap to forbid and cost nothing here.
  assert.match(csp, /frame-ancestors 'none'/);
  assert.match(csp, /object-src 'none'/);
  assert.match(csp, /base-uri 'self'/);
  // Forms must not post to an attacker-controlled origin.
  assert.match(csp, /form-action 'self'/);
  // No plugin, worker or manifest execution from arbitrary origins.
  assert.match(csp, /worker-src 'self'/);
  assert.match(csp, /manifest-src 'self'/);
});

test("the CSP does not allow arbitrary third-party script or connections", async () => {
  const csp = (await headers()).get("content-security-policy")!;

  const scriptSrc = csp.match(/script-src ([^;]+)/)?.[1] ?? "";
  assert.doesNotMatch(scriptSrc, /https?:|\*/, "scripts must be same-origin only");

  const connectSrc = csp.match(/connect-src ([^;]+)/)?.[1] ?? "";
  assert.doesNotMatch(connectSrc, /\*/, "connections must be same-origin (or websocket)");
});

test("HSTS is long-lived and covers subdomains", async () => {
  const hsts = (await headers()).get("strict-transport-security")!;

  assert.match(hsts, /includeSubDomains/);
});
