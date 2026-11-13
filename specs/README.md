# Specs

Everything built here starts as a spec in this folder.

| Path | What lives there |
| --- | --- |
| `constitution.md` | The nine articles every spec, plan and pull request is checked against |
| `_templates/` | Starting points: `feature-spec.md`, `implementation-plan.md`, `tasks.md`, `domain-spec.md`, `adr.md` |
| `features/NNN-slug/` | One folder per feature: `spec.md` (what and why), `plan.md` (how), `tasks.md` (test-first work list) |
| `domain/` | Rules that cut across features: ledger, state machines, invariants |
| `api/openapi.yaml` | The only definition of HTTP behavior |

## IDs

- Acceptance criteria: `AC-<AREA>-NNN`, defined as rows in a feature spec's criteria table.
- Domain invariants: `INV-<AREA>-NNN`, defined as rows in a domain spec's invariants table.
- IDs are permanent. Never reuse or renumber one. Retire a criterion by marking it "Retired" in its row, not by deleting it.
- `tools/check_traceability.py` reads these tables, so keep the ID in the first column.

## Status of the specs

| Spec | Status | Blocking questions |
| --- | --- | --- |
| features/001-contributions | Implemented | Backend and web complete; manual Paystack sandbox (T096) remains before the first live payment. |
| features/002-payouts | Ready | Spec, plan and tasks reviewed and merged 2026-11-09; ADR 0016 accepted. Implementation (V9, OpenAPI, code) has not started. |
| features/003-organizer-identity | In progress | Backend identity, goals, organizer web, invariants and hardening are implemented and E2E-tested. Production SMS adapter (Arkesel) is coded with sandbox passing; live delivery awaits sender approval. Paystack MoMo resolution is implemented; live checks and a few hardening tests (T174/T180/T193/T195/T198) remain; ADRs 0009/0010 accepted. |
| features/005-refunds-and-operator-tooling | Draft | P1 pre-payout refunds and suspense reconciliation are specified. Open P1 questions: refund fee policy, operator auth/roles, initiation, in-flight-payout rule. See the spec's open questions. |
| domain/ledger | Accepted | Settlement model, payout fee bearer and PLATFORM_FEE decided in ADRs 0006 and 0007; contribution invariants implemented. Payout invariants follow FEAT-002. |
| domain/contribution-state-machine | Accepted | Early- and late-success transitions implemented and tested. |
| domain/organizer-identity | Accepted | Session, challenge and phone invariants for FEAT-003 and FEAT-004. |
| domain/payout-state-machine | Accepted | States, transitions, reservations, approval, mandatory hold, freeze/cancel/retry and payout invariants for FEAT-002. |
| domain/settlement | Accepted | Phase 1 schema/repository and goal-owned eligibility (ADR 0014) implemented; CODEOWNER review of ledger/payment changes remains. |
