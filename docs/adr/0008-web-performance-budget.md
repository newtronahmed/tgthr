# ADR 0008: Web performance budget

- Status: Accepted
- Date: 2026-11-06
- Deciders: Ahmed Zubairu
- Constitution articles touched: VI

## Context

Guests arrive from WhatsApp on mid-range phones and low-bandwidth connections. Constitution VI.2 requires a page-weight budget set by ADR before the first public release, and the PWA must stay installable without shipping a heavy client.

## Decision

We will keep the total client JavaScript emitted by the web build at or below **220 KB gzipped** and CSS at or below **20 KB gzipped**. `web/scripts/check-budget.mjs` measures the build output and CI fails when either bound is exceeded. The initial measured baseline on 2026-11-06 was **183.2 KB JS** and **9.1 KB CSS** gzipped.

## Consequences

**Good**

- A hard, automated ceiling blocks accidental dependency or bundle growth.
- The public checkout stays fast in WhatsApp's in-app browser.

**Costs and risks**

- The budget is intentionally blunt (whole-build totals, not per route); a single heavy route is caught only if it pushes the total over the limit.
- Raising the budget needs a new ADR or a superseding decision.

## Alternatives considered

| Option | Why not |
| --- | --- |
| No budget until launch | Constitution VI.2 requires one before the first public release |
| Per-route budgets only | Next/Turbopack did not expose a stable per-route chunk manifest at the time |
| Lighthouse-only gate | Network-bound and flaky in CI; the byte budget is deterministic |
