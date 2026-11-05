# ADR 0001: One modular monolith

- Status: Accepted
- Date: 2026-11-04
- Deciders: Ahmed Zubairu
- Constitution articles touched: VII

## Context

The MVP must reach a December launch with a very small team. Payments and the ledger need strong consistency: confirming a contribution and posting its ledger entries must succeed or fail together.

## Decision

We will build one Spring Boot deployable organized as modules (identity, group and goal, contribution, ledger, payout, notification), with boundaries enforced by Spring Modulith verification in the test suite.

## Consequences

**Good**

- One database transaction covers confirmation and posting.
- One pipeline, one deployment, one place to debug.
- Modules can be extracted later if a real need appears.

**Costs and risks**

- Boundaries are only as strong as the tests that enforce them, so `ModularityTests` must stay in the required CI checks.
- A bug in one module can take the whole process down; health checks and rollback cover this.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Microservices | Distributed transactions and operational load are unjustified at this size |
| Plain layered monolith with no module rules | Boundaries erode quickly under deadline pressure |
