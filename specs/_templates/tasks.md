# Tasks: <Feature title>

<!--
Copy to specs/features/NNN-<slug>/tasks.md once the plan is Approved.
Order is test-first: for each criterion, the failing test comes before the code.
[P] marks tasks that can run in parallel because they touch different files.
Tick a task only when its tests are green and the pull request is merged.
-->

## Setup

- [ ] T001 <shared scaffolding this feature needs, if any>

## Slice 1 (P1): <slice name>

Criteria: AC-AREA-001, AC-AREA-002

- [ ] T010 Write the failing test for AC-AREA-001 in `<path>`
- [ ] T011 Implement until T010 is green
- [ ] T012 [P] Write the failing test for AC-AREA-002 in `<path>`
- [ ] T013 Implement until T012 is green
- [ ] T014 Refactor; keep the suite green
- [ ] T015 Update `specs/api/openapi.yaml` and regenerate clients (if the contract changed)

## Slice 2 (P2): <slice name>

Criteria: AC-AREA-003

- [ ] T020 Write the failing test for AC-AREA-003 in `<path>`
- [ ] T021 Implement until T020 is green

## Hardening

- [ ] T090 Failure-path tests for every row in the spec's failure table
- [ ] T091 Log events, metrics and alerts listed in the spec
- [ ] T092 Run `make check`; remove any remaining `@Disabled` markers for this feature
- [ ] T093 Update the spec status to Implemented
