# Group Collections

A platform for collecting money for shared goals (funerals, weddings, trips, rent, gifts, community projects) in Ghana. Anyone can pay by mobile money from a WhatsApp link with no install. Organizers and returning members use an installable web app (PWA).

This repository is built **spec first** and **test first**. Read `specs/constitution.md` before changing anything.

## Layout

```
specs/
  constitution.md       the rules every spec, plan and pull request is checked against
  _templates/           feature spec, implementation plan, task list, domain spec, ADR
  features/NNN-slug/    one folder per feature: spec.md, then plan.md, then tasks.md
  domain/               ledger rules, state machines, invariants
  api/openapi.yaml      the single API contract
docs/
  adr/                  architecture decision records
  runbooks/             manual operational procedures (Paystack sandbox)
  solution.md           the solution document
backend/                Spring Boot 3, Java 21, Gradle (modular monolith)
web/                    Next.js PWA: public goal, checkout and receipt (design prototype in web/design)
tools/
  check_traceability.py spec ID to test mapping check
  momo-fake/            the local fake payment provider (to build)
infra/                  docker-compose.yml for local PostgreSQL and Redis
```

## How a change flows

1. **Specify**: copy `specs/_templates/feature-spec.md` to `specs/features/NNN-slug/spec.md`. Write numbered criteria (`AC-AREA-001`). Resolve every `[NEEDS CLARIFICATION]` on P1 stories.
2. **Plan**: copy the implementation plan template. Fill in the Constitution Check.
3. **Task**: copy the tasks template. Tasks are ordered test first.
4. **Red**: remove `@Disabled` from the pending test for a criterion, or write a new one tagged `@Criterion("ID")`. Watch it fail.
5. **Green and refactor**: implement until the suite passes.
6. **Check**: run `make check`. CI runs the same.

## Everyday commands

| Command | What it does |
| --- | --- |
| `make up` | Start PostgreSQL and Redis locally |
| `make test` | Run backend tests |
| `make trace` | Check that every spec ID has a test |
| `make trace-strict` | Same, but fail while any criterion is only covered by a disabled test |
| `make lint-api` | Lint `specs/api/openapi.yaml` with Spectral |
| `make check` | Everything the backend/spec CI runs |
| `make web-check` | Build and type-check the Next.js app |

## Status

FEAT-001 is merged and implemented (backend + web; PWA and browser E2E done). The manual Paystack sandbox remains before the first live payment.

FEAT-003 organizer identity, goal creation and the organizer dashboard are merged (PR #2) with invariants and hardening. Production SMS/Paystack adapters and live sandbox checks remain.

FEAT-002 payout spec, plan and tasks are Ready (ADR 0016); payout implementation has not started. AC-CONTRIB-001 to AC-CONTRIB-007 and AC-CONTRIB-011 to AC-CONTRIB-018 are implemented, along with INV-LEDGER-001/002/004/006 and INV-STATE-001/002/003 (creation and prompt, amount/acceptance rules, idempotency, signed, failed, successful, duplicate, early and late callbacks with balanced append-only postings, phone-free public bodies, fees, transition table, ledger invariants). 1 spec check remains pending (INV-LEDGER-003, FEAT-002 payout scope). `make check` passes with JDK 21 and the committed Gradle wrapper.
