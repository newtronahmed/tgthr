# Feature NNN: <Title>

<!--
Copy this file to specs/features/NNN-<slug>/spec.md.
Write WHAT and WHY here, never HOW. Technology choices belong in the implementation plan.
Replace every <placeholder>. Mark anything unknown as [NEEDS CLARIFICATION: <question>].
-->

| Field | Value |
| --- | --- |
| Feature ID | FEAT-NNN |
| Status | Draft / In review / Ready / Implemented |
| Owner | <name> |
| Area code | <AREA, used in criterion IDs such as AC-AREA-001> |
| Created | <YYYY-MM-DD> |
| Related | <links to ADRs, domain specs, wireframes> |

## Problem and outcome

<Who is hurt today, how, and what is true when this feature works. Two to four sentences.>

## Actors

- <Actor>: <what they want>

## User stories

Prioritized. Each P1 story alone must deliver a usable slice.

| Priority | Story |
| --- | --- |
| P1 | As a <actor>, I <do something>, so that <outcome>. |
| P2 | As a <actor>, I <do something>, so that <outcome>. |

## Scope

**In scope**

- <item>

**Out of scope**

- <item, and where it will be specified instead>

## Rules

Numbered business rules that the criteria below exercise.

1. <rule>

## Acceptance criteria

One row per criterion. IDs are permanent: never reuse or renumber. Every ID must be referenced by a test (`@Criterion("AC-AREA-001")`).

| ID | Priority | Given / When / Then | Test level |
| --- | --- | --- | --- |
| AC-AREA-001 | P1 | Given <state>, when <action>, then <observable result>. | Unit / Integration / Contract / E2E |

## Failure modes and edge cases

| Situation | Expected behavior | Criterion |
| --- | --- | --- |
| <what goes wrong> | <what the system does> | <AC id or "gap: needs a criterion"> |

## Data and ledger impact

- Entities created or changed: <list>
- Ledger entries posted: <entry types, or "none">
- Invariants touched: <INV ids>

## API impact

- Endpoints added or changed in `specs/api/openapi.yaml`: <list>
- Backward compatibility: <additive / breaking, and why>

## Security, privacy and fraud

- <who could abuse this and how, and what prevents it>
- <personal data handled and where it is exposed>

## Observability

- Events logged: <list>
- Metrics: <list>
- Alerts: <list>

## Non-functional requirements

- <measurable target, for example "status endpoint answers within X ms at the 95th percentile">

## Open questions

- [NEEDS CLARIFICATION: <question>] Owner: <name>. Needed by: <date or milestone>.

## Definition of ready

- [ ] Every P1 story has at least one criterion
- [ ] No open question blocks a P1 story
- [ ] Failure modes each map to a criterion or an explicit gap
- [ ] API impact is reflected in `openapi.yaml` (or a pull request does so)
- [ ] Constitution articles touched are named in the plan
