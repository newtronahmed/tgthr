## What and why

<One or two sentences.>

## Spec

- Spec or domain doc: <path>
- Criteria covered: <AC-AREA-001, ...>
- Spec changed in this pull request: yes / no (if behavior changed, it must be yes)

## Checklist

- [ ] The failing test came first (red, then green)
- [ ] `make check` passes
- [ ] Independent review recorded in `docs/reviews/FEAT-<id>.md` (slice boundary or this PR)
- [ ] `specs/api/openapi.yaml` updated if the contract changed
- [ ] No floating point on money; no stored balances; no updates or deletes on ledger rows
- [ ] Public responses contain no phone numbers
- [ ] New failure paths log a structured event and increment a metric
- [ ] ADR added for any new dependency, datastore or architectural change
- [ ] Constitution articles touched: <list, or none>
