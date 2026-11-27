# Review: fix/005-refund-verification-window

- Date: 2026-11-27
- Range: `main..fix/005-refund-verification-window` (3 commits, head `f16e52c` before the review fixes)
- Reviewer: fresh-context subagent (`reviewer`, `commandcode/z-ai/glm-5.3-flash#high`)
- Verdict: pass with follow-ups → follow-ups fixed below

## Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Should-fix | `refund/RefundService:verify` | **Correct.** I claimed the new `findByIdForUpdate` "serializes this operator check against the scheduled job", but `verify` is not `@Transactional`, so `for update` ran in autocommit and locked nothing. The guarantee did not exist. The reviewer also correctly warned that the obvious fix — annotating `verify` `@Transactional` — would put the provider HTTP call inside a row lock, violating CLAUDE.md rule 27. | CLAUDE.md rule 25 | Fixed: reverted to `findById`, replaced the comment with why the lock is absent and where correctness actually lives (the CAS guards). Verified that `claimVerification` (`verification_exhausted_at is null`) and `rearmVerification` (`is not null`) can never both match, so the race is genuinely closed by the guards. |
| Should-fix | ADR 0020, spec rule 10 | **Partially correct, one real error.** The totals are right (9.17d, and 28 attempts reaches 11.17d) — the reviewer's own alternative arithmetic (9.66d) does not match `backoff`. But the ADR said the 12h tail applies from attempt 9, when the cap actually starts at attempt 7 (17 capped waits, not 16). | Article II.2 | Fixed: ADR now states the doubling 15m→…→8h then 12h from attempt 7, with the count. |
| Note | `refund/RefundService:rearmExhaustedVerification` | `VERIFICATION_REARMED` hardcoded its from/to states instead of re-reading the row. | Article I traceability | Fixed: re-reads state before appending. |
| Note | `RefundVerificationWindowTest` | The schedule was a hand-copied mirror of `backoff`, so a change to the job alone could pass the test. | Test honesty | Fixed: the test now builds the job through Spring with no `@TestPropertySource`, so it reads the shipped `application.yml`, and calls the job's own `backoff`/`initialDelay`/`maxAttempts`. Verified it still fails (2/2) when the config is temporarily reverted to `PT5M`/`PT6H`/8. |
| Note | `RefundVerificationWindowTest:resolveDefault` | Splitting `${NAME:default}` at the first `:` would truncate a default containing a colon. | — | Accepted; current values contain none. |

## Setup checks

- Nullable SQL parameters cast/tested — not applicable (`rearmVerification` params are non-null).
- Read-check-then-write lock taken by all writers + concurrency test — **pass**, after the fix: the
  ineffective lock was removed rather than papered over, and the guards are complementary by
  construction.
- No external I/O under a row lock — **pass**, and the fix preserves this by explicitly not making
  `verify` transactional.
- Criterion not marked covered while a phase pending — pass (`AC-REFUND-011` active in both ITs).
- OpenAPI matches behaviour — pass (no endpoint change).

## Unverified

- Runtime behaviour under a real Postgres; the autocommit reasoning is from code reading.
- The reviewer could not run tests (read-only agent). `./gradlew check` was run by the parent.

## Follow-ups

- T402 is ticked in `tasks.md` but `RefundConfigIT` does not exist. Owner: refunds slice owner.
- Adopt ADR 0020 by changing its status from Proposed to Accepted once the owner signs off.
