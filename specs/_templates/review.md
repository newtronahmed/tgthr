# Review: <scope>

- Date: <YYYY-MM-DD>
- Range: `<base>..<head>`
- Reviewer: <fresh-context subagent / CODEOWNER>
- Verdict: <pass / pass with follow-ups / blocked>

## Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker / Should-fix / Note | `file:line` | <what is wrong and why it matters> | AC-... / INV-... / Article ... | <fix, task id, or accepted> |

## Setup checks

- [ ] Nullable SQL parameters are cast/typed and a test executes the null branch.
- [ ] Any read-check-then-write lock is also taken by the writer that creates the guarded rows; a concurrency test exists.
- [ ] A criterion is not marked covered while a phase is pending; split it or keep a pending test.
- [ ] Unique constraints match the stated cardinality (per contributor vs per contribution).
- [ ] OpenAPI descriptions match the actual comparison/behaviour, including domain validation that returns a specific 422 code.
- [ ] No external I/O (SMS/provider) runs while holding a database row lock; send-after-commit/outbox and failure cleanup are tested.
- [ ] One-time codes/links and message bodies are redacted from logs.
- [ ] Every state-transition side effect has code or a named task.

## Follow-ups

- <owner and task id, or "none">
