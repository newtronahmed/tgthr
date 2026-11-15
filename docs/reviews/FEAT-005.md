# Review: FEAT-005 refund provider hardening

- Date: 2026-11-15
- Range: `3c26564..4135065` (`feat/005-refund-provider-hardening`)
- Reviewer: fresh-context subagent (read-only); CODEOWNER review remains T494
- Verdict: pass with follow-ups (one blocker fixed before merge)

This review covers the provider-hardening follow-up to the merged refund core (PR #38,
`3c26564`). The core posting, reservation and operator flows were reviewed in that PR; this
range changes `PaystackRefundProvider`, its fake and its contract tests.

## Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker (fixed) | `refund/PaystackRefundProvider.verify` | Paystack documents `GET /refund?transaction=` as returning a JSON **array**, but verification fed the array to the single-object mapper. Amount and currency are absent on an array node, so every unknown-outcome refund stayed `PENDING` and a successful refund could never post. | AC-REFUND-003, AC-REFUND-004 | Fixed in `4135065`: `mapRefundList` selects the latest entry whose transaction, amount and currency match; empty, non-matching and latest-entry branches each have a test. |
| Should-fix | `refund/RefundCompletionService`, `refund/RefundService.verify` | `PROVIDER_PENDING` has no automatic exit. Only an operator `POST /operator/refunds/{id}/verify` re-checks the provider, so an unwatched refund holds its `A` reservation on the goal pool indefinitely. | AC-REFUND-004, spec failure table | T497. |
| Should-fix | `refund/RefundProvider.RefundResult.pending` | `PENDING` carries no reason code. Timeout, 409 duplicate, 429/5xx, missing data and amount/currency mismatch are indistinguishable, and every verify republishes `REFUND_PROVIDER_PENDING`. Operators cannot triage and alerts cannot de-duplicate. | NFR-OBS, Article VII | T498. |
| Should-fix | `refund/RefundCompletionService` | Loss posting is decided by the static `collections.refunds.partner-retains-processing-fee` flag; the provider's `fully_deducted`/`deducted_amount` is ignored. Until T401 records the real Paystack behaviour the ledger can book a fee loss that the provider actually returned. | AC-REFUND-007, ADR 0018, T401 | T499. The flag stays `true` until the spike is signed off. |
| Note | `refund/PaystackRefundProvider.refund` | The provider generates the refund reference and retries rely on Paystack returning 409. A deterministic client reference, persisted before the call, would make reconciliation and duplicate detection independent of the provider's status signal. | AC-REFUND-003, T401 | T500. |

## Setup checks

- [x] Nullable SQL parameters — no SQL changed in this range; V15 remains covered by the core review.
- [x] Read-check-then-write locking — the request path is unchanged; the provider call still runs after the request commits and outside the goal lock.
- [ ] A criterion is not marked covered while a phase is pending — the trace is honest (12 pending, 0 missing), but the `PROVIDER_PENDING` exit has no criterion yet (T497).
- [x] Unique constraints — no schema change.
- [x] OpenAPI — refund request/verify descriptions still match. The 409 on the request is the idempotency refusal; a provider 409 is internal and maps to `PENDING`.
- [x] No external I/O while holding a row lock — `callProvider` runs after commit; unknown results are applied in a separate transaction.
- [x] One-time codes and message bodies — none touched; logs carry only refund/contribution IDs and states.
- [ ] Every state-transition side effect has code or a named task — `PENDING -> PROVIDER_PENDING -> SUCCEEDED|FAILED` exists, but the only exit from `PROVIDER_PENDING` is an operator call (T497).

## Verification

- `./gradlew test --tests 'com.groupcollections.refund.*'` — green (18m31s); `PaystackRefundProviderTest` covers success, request rejection, timeout, 409/429/5xx, null and non-object bodies, list selection, latest match, empty list, unmatched amount/transaction, unknown status, verify 404 and 200 `status:false`.
- `./gradlew spotlessCheck` — green.
- `python3 tools/check_traceability.py` — 111 defined / 99 active / 12 pending / 0 missing.
- `make lint-api` — clean.
- Full `make check` is not the local gate: one run timed out at 2400s and a later run lost the Testcontainers Postgres port. GitHub CI is the full-suite gate for this PR.

## Follow-ups

- T497: scheduled `PROVIDER_PENDING` exit with bounded re-verification and escalation.
- T498: pending reason codes and alert de-duplication.
- T499: gate loss posting on the provider's fee-return fields once T401 answers.
- T500: deterministic client refund reference for verification and reconciliation.
- T494 (CODEOWNER review of the refund posting) and T401 (sandbox fee-return spike) remain open in `tasks.md`; neither blocks this hardening diff.
