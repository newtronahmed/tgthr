# Implementation plan: <Feature title>

<!--
Copy to specs/features/NNN-<slug>/plan.md once the spec is Ready.
This is the HOW: modules, data, contracts and test strategy. Keep it short enough to review in one sitting.
-->

| Field | Value |
| --- | --- |
| Spec | specs/features/NNN-<slug>/spec.md |
| Status | Draft / Approved |
| Author | <name> |

## Summary

<One paragraph: the approach and the main trade-off.>

## Constitution check

Answer every row. "N/A" needs a reason.

| Article | Does this plan comply? | Notes |
| --- | --- | --- |
| I. Money is exact and traceable | Yes / No / N/A | |
| II. Spec first | Yes / No / N/A | |
| III. Test first | Yes / No / N/A | |
| IV. Idempotent and consistent | Yes / No / N/A | |
| V. Trust is a feature | Yes / No / N/A | |
| VI. Guest first, low bandwidth | Yes / No / N/A | |
| VII. Simple until proven otherwise | Yes / No / N/A | |
| VIII. Security and privacy | Yes / No / N/A | |
| IX. Observable | Yes / No / N/A | |

## Technical context

- Modules touched: <module names and why>
- New dependencies: <none, or each one with an ADR link>
- Data stores: <tables added or changed>

## Design

### Domain and data

<Entities, state changes, ledger postings. Link domain specs rather than copying them.>

### API

<Operations added or changed. The contract lives in openapi.yaml; list only the differences.>

### Sequence for the main path

<Numbered steps, naming the module that does each.>

## Test strategy

One row per criterion in the spec.

| Criterion | Test level | Test class or file | Notes |
| --- | --- | --- | --- |
| AC-AREA-001 | Integration | | |

## Migrations

- Scripts: <Flyway file names>
- Backward compatible with the running version: Yes / No (if no, explain the two-step release)

## Rollout

- Feature flag or config: <name, default>
- Observability added: <events, metrics, alerts>
- Rollback: <how>

## Risks

| Risk | Mitigation |
| --- | --- |
| | |

## Complexity tracking

Fill only if the Constitution check has a "No".

| Violation | Why it is needed | Simpler alternative rejected because |
| --- | --- | --- |
| | | |
