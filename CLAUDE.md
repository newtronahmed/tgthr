# Instructions for AI coding agents

Read `specs/constitution.md` first. It is binding.

## Workflow

1. Find the spec for the work in `specs/features/` or `specs/domain/`. If there is none, or it has an open `[NEEDS CLARIFICATION]` on a P1 story, stop and ask rather than guess.
2. Work test first. Take the next pending criterion (a `@Disabled` test tagged `@Criterion("ID")`), enable it, run it and confirm it fails for the right reason, then implement the minimum to pass.
3. Run `make check` before you say the work is done. Report what you ran and the result.
4. Independent review at each slice boundary. Spawn read-only reviewer(s) with fresh context; give them the constitution, the relevant specs and ADRs, the slice diff and the result of `make check`. Record findings in `docs/reviews/FEAT-<id>.md`. Blockers (constitution MUST violations or wrong money behaviour) are fixed test-first before the next slice starts; should-fix items need an owner in `tasks.md`. AI review supplements — it never replaces — the CODEOWNER review required for `ledger/` and `payments/`.

## Rules that are easy to get wrong

- Money is integer pesewas. Never `double` or `float`. Never store a balance; derive it from ledger entries.
- Never update or delete a ledger row. Corrections are new entries.
- Idempotency comes from database unique constraints, not Redis locks or in-memory checks.
- State changes go through the state machine in `specs/domain/`. Do not add transitions without amending the spec first.
- Public responses never contain phone numbers.
- Do not edit an accepted ADR's decision. Supersede it with a new ADR.
- Do not add a dependency, datastore or service without an ADR.
- Do not call a real payment provider from tests. Use the fake.
- Never commit secrets. Configuration comes from environment variables.

## Where things go

- New criterion: add a row to the spec table with a new permanent ID, then a tagged test.
- New endpoint: change `specs/api/openapi.yaml` first, in the same pull request.
- New decision with lasting consequences: a numbered ADR in `docs/adr/`.
- Review record: append a section to `docs/reviews/FEAT-<id>.md` using `specs/_templates/review.md`.
