# Contributing

## Branches and commits

- Trunk-based development: short-lived branches off `main`, merged by pull request.
- [Conventional Commits](https://www.conventionalcommits.org/): `feat(contribution): ...`, `fix(ledger): ...`, `docs(spec): ...`, `test(contribution): ...`.
- Commit the failing test before the implementation where practical, so the history shows red then green.

## Pull requests

Every pull request:

- links the spec it implements or amends;
- lists the criterion IDs it covers;
- passes `make check`;
- changes the spec in the same pull request if behavior changed;
- links the independent review record in `docs/reviews/FEAT-<id>.md` and its verdict.

Changes under `specs/` and `backend/src/main/java/com/groupcollections/ledger/` need a CODEOWNER review.

## Specs

Use the templates in `specs/_templates/`. Criterion and invariant IDs are permanent: never reuse or renumber them. A spec is **Ready** only when its definition-of-ready checklist is complete.

## Constitution changes

Open a pull request that edits `specs/constitution.md`, adds an ADR, and bumps the version line. See the Governance section.

## Local setup

Requirements: JDK 21 (set `JAVA_HOME` to it), Docker, Python 3.10 or later, Node 20 or later (for API linting and, later, the web app).

```
make up      # PostgreSQL and Redis
make check   # specs, tests and lint
```
