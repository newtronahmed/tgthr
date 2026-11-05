# Project Constitution

Version 1.0.0 · Ratified 2026-11-04 · Last amended 2026-11-04

This constitution governs how Group Collections is specified, built, tested and operated. Specs, plans and pull requests are checked against it. Where a rule here conflicts with convenience, the rule wins until it is amended.

**MUST** and **MUST NOT** are mandatory. **SHOULD** is the default; deviating needs a written reason in the plan.

## Article I. Money is exact and traceable

1. Every money movement MUST be an immutable, append-only ledger entry. Balances are derived, never stored or edited.
2. Postings are double-entry and MUST net to zero per business event.
3. Amounts MUST be integer pesewas with a currency code. Floating point MUST NOT touch money.
4. Any change to ledger, payment or payout code MUST carry property or invariant tests and a review from a CODEOWNER of `ledger/` or `payments/`.
5. Money MUST NOT be silently dropped. A confirmed provider payment we cannot match becomes an alert, never a discarded event.

## Article II. Spec first

1. No production code is written without a merged spec containing numbered acceptance criteria (`AC-AREA-NNN`) or domain invariants (`INV-AREA-NNN`).
2. Specs change by pull request, before or together with the code they describe. If code and spec disagree, the spec is amended first or the code is fixed; never leave both.
3. Open questions are marked `[NEEDS CLARIFICATION: ...]`. A spec with open markers on a P1 story is not ready for planning.
4. `specs/api/openapi.yaml` is the single source of truth for HTTP behavior. A breaking change needs a new major version path.

## Article III. Test first

1. Red, green, refactor. The failing test MUST exist, and where practical be committed, before the implementation.
2. Every criterion and invariant MUST be referenced by at least one test through `@Criterion("ID")` (backend) or `criterion("ID")` (web). CI fails on an unreferenced or unknown ID.
3. No automated test calls a real payment provider. Provider behavior is tested against the in-repo fake; a manual sandbox suite runs before each release.
4. Time, randomness and IDs MUST be injected so tests are deterministic.
5. Gates: line coverage at least 80% overall and 90% in `ledger` and `payments`; a mutation score threshold on those packages, recorded in an ADR when first measured.

## Article IV. Idempotent and consistent by construction

1. Every external callback and every retried command MUST be idempotent, enforced by a database constraint. Caches and locks MAY speed this up and MUST NOT be what makes it correct.
2. State changes MUST go through the state machine in the domain spec. An illegal transition is logged and ignored, never applied.
3. A contribution is confirmed and its ledger entries are posted in one transaction.

## Article V. Trust is a feature

1. A payout destination is locked once set. Changing it needs a fresh OTP and notifies every contributor.
2. Every payout waits in a hold window, can be cancelled by the organizer and reported by contributors, and notifies contributors when sent.
3. Public responses MUST NOT contain phone numbers. Anonymous gifts show a masked name publicly, and every read of the identity behind one is logged.
4. Surplus defaults to the beneficiary. A welfare reserve exists only if the organizer opted in and the checkout page says so.
5. Settings that affect contributors' money (approver rule, surplus rule, hold window) are visible on the public goal page.

## Article VI. Guest first, low bandwidth

1. A guest MUST be able to pay without installing anything or creating an account.
2. Public pages are server-rendered, work in WhatsApp's in-app browser on iOS and Android, and ship minimal JavaScript. A page weight budget is set by ADR before the first public release.
3. Nothing essential depends on push notifications or installation. They enhance the product and never gate it.

## Article VII. Simple until proven otherwise

1. One deployable modular monolith. A new service, datastore or major dependency needs an ADR.
2. Module boundaries are enforced by tests. A module reaches another only through its published API.
3. Work outside the current spec's scope (susu, native app, public discovery) is not built ahead of need.

## Article VIII. Security and privacy

1. Secrets MUST NOT be in the repository. Configuration comes from the environment.
2. Personal data is minimized. Phone numbers are encrypted at rest and looked up by keyed hash.
3. Authorization is enforced on the server for every non-public endpoint. The client is never trusted.
4. Obligations under Ghana's Data Protection Act, 2012 (Act 843) and any payment regulation are confirmed with counsel before the first live payment. This repository is not legal advice.

## Article IX. Observable

1. Every failure path logs a structured event with a correlation ID and increments a metric.
2. Anything stuck, mismatched or unreconciled raises an alert. Silent failure is a defect.

## Governance

- **Amendments**: a pull request that changes this file and adds an ADR, approved by the repository owner. Bump the version: major for removed or reversed rules, minor for new rules, patch for wording.
- **Constitution Check**: every implementation plan includes one. A violation must be justified under "Complexity tracking" or removed.
- **Review**: a reviewer MAY block a pull request for a constitution violation without further justification.
