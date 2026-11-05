# Domain spec: <Concept>

<!--
Copy to specs/domain/<concept>.md. Domain specs state rules that hold across features:
state machines, ledger postings and invariants. Each invariant gets a permanent ID and a test.
-->

| Field | Value |
| --- | --- |
| Status | Draft / Accepted |
| Area code | <AREA> |
| Related features | <FEAT ids> |

## Purpose

<What this concept is and why other specs depend on it.>

## Terms

| Term | Meaning |
| --- | --- |
| <term> | <definition> |

## States and transitions

| From | Event | To | Side effects |
| --- | --- | --- | --- |
| <state> | <event> | <state> | <ledger entries, notifications> |

## Invariants

IDs are permanent. Every ID must be referenced by a test (`@Criterion("INV-AREA-001")`).

| ID | Statement |
| --- | --- |
| INV-AREA-001 | <A statement that is always true and can be tested.> |

## Open questions

- [NEEDS CLARIFICATION: <question>]
