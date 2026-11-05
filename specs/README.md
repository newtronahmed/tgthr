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
| features/001-contributions | Ready | None. Plan and tasks drafted 2026-11-05. |
| domain/ledger | Draft | None; settlement model, payout fee bearer and PLATFORM_FEE decided in ADRs 0006 and 0007. |
| domain/contribution-state-machine | Draft | None; late-success transitions added 2026-11-05. |
