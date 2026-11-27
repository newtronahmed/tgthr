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

## Review: T401 sandbox spike and refund provider hardening

- Date: 2026-11-26
- Range: `main` working tree (uncommitted)
- Reviewer: agent session against the live Paystack test API; CODEOWNER review still required (T494)
- Verdict: pass with follow-ups

### Why this slice exists

T401 was the last open question blocking the refund go-live: does Paystack return the processing fee?
The spike answered it and, in doing so, showed that three adapter assumptions did not match the live
API. Evidence and request/response captures are in `docs/runbooks/refund-operations.md` section 9.

## Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `refund/PaystackRefundProvider:refund` | A duplicate submit answers `400` + `code=transaction_reversed`, not `409`. The adapter classified it as `PROVIDER_REJECTED`, releasing the reservation and marking the refund `FAILED` while Paystack was refunding the contributor. | AC-REFUND-003, AC-REFUND-004, spec rule 7 | Fixed. Now `PENDING`/`CONFLICT`, carrying the existing refund id. `duplicateRefundAnsweredWith400TransactionReversedIsPendingAndKeepsTheExistingReference`. |
| Blocker | `refund/PaystackRefundProvider:verify` | `GET /refund?transaction=` needs the numeric transaction id; the app stores the charge reference string, so the fallback matched nothing. A refund with an unknown submit outcome could never be resolved and held its reservation until exhaustion. | AC-REFUND-004, AC-REFUND-011 | Fixed. Deterministic client reference `tgthr-refund-<refund id>` is sent and verified through `?reference=`, which the spike confirmed resolves it. T500 adapter half. |
| Should-fix | `specs/.../tasks.md:T499` | `fully_deducted`/`deducted_amount` describe the payout deduction, not the fee. Both refunds reported fully deducted while the partner retained `F`, so gating the loss set on them would gate nothing. | AC-REFUND-007, ADR 0018 | T499 rewritten in place; do not implement as originally written. |
| Should-fix | `application.yml:refunds.verification` | The default backoff (5m, 8 attempts, 6h cap ≈ 16.6h) exhausts well before Paystack's ~11-day refund window, so every real refund would escalate. | AC-REFUND-011 | Not changed: spec rule 10 makes the timeout configuration, and tuning it needs a spec amendment. Recorded as a follow-up. |

## Setup checks

- [x] Nullable SQL parameters cast/typed — no SQL changed in this slice.
- [x] Read-check-then-write locks — no locking changed; `RefundService` and the verification job are untouched.
- [x] A criterion is not marked covered while a phase is pending — no `@Criterion` tests were enabled or disabled; traceability 112 defined / 108 active / 4 pending / 0 missing.
- [x] Unique constraints match cardinality — no migration changed.
- [x] OpenAPI matches behaviour — no endpoint or schema changed; the provider is internal.
- [x] No external I/O while holding a row lock — unchanged; the provider call still runs after commit.
- [x] One-time codes and message bodies redacted — logs still carry only refund/contribution IDs and states. The spike used test-mode keys only and no payer data was recorded.
- [x] Every state-transition side effect has code or a named task — unchanged by this slice.

## Verification

- `./gradlew spotlessCheck test` — green; 345 tests across 73 suites, 0 failures, 4 skipped (the known pending criteria).
- `python3 tools/check_traceability.py` — 112 defined / 108 active / 4 pending / 0 missing, OK.
- `npx @stoplight/spectral-cli lint specs/api/openapi.yaml` — no errors.
- The fake now mirrors the live API for the three behaviours above (numeric-id `?transaction=`, `?reference=`, `400 transaction_reversed`, caller-supplied reference), so the tests fail if the adapter regresses to the old assumptions.

## Follow-ups

- T494: fresh CODEOWNER review of the refund posting. Still the only hard gate on enabling the provider.
- T499: rewrite before implementing (see the finding above).
- T500: persist the client reference on the refund row for reconciliation; the adapter half is done.
- Verification window: amend spec rule 10 to match Paystack's refund settlement window, then raise the backoff ceiling. Otherwise refunds escalate while still legitimately pending.

## Review: T500 persisted client refund reference

- Date: 2026-11-27
- Range: `main` working tree (uncommitted, on top of the T401/T500 adapter slice above)
- Reviewer: agent session; CODEOWNER review still required (T494)
- Verdict: pass

### What changed

T500 originally read "send **and persist**". The adapter half already sent `tgthr-refund-<refund id>`
and verified through `GET /refund?reference=`, but the value existed only as a derivation. This
slice persists it, so reconciliation does not depend on that derivation staying stable.

| Change | Detail |
| --- | --- |
| `V19__refund_client_reference.sql` | Adds `refunds.client_reference`, backfills `'tgthr-refund-' \|\| id`, then sets `NOT NULL` and a unique constraint. |
| `RefundReferences` | One place that derives the reference, used by the insert and documented against Paystack's 8–64 character rule. |
| `RefundRepository` | Writes the reference in the same insert as the refund row, before the after-commit provider call; maps it on every read. |
| `PaystackRefundProvider` | Sends the persisted `clientReference` and resolves pending refunds with it. Falls back to the identical derivation only for fixtures/rows without one. |
| `RefundView` / OpenAPI / web | Exposes `clientReference` so the console can show an operator which reference to reconcile when `provider_reference` is still null. |

### Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `PaystackRefundProvider:existingRefund` | The captured live `data.existing_refunds` entries carry only `id`, `reference`, `status`. The old check required a matching `amount`, so every live duplicate discarded the reference it had just been sent. The fake had invented `amount`/`currency`, which hid this. | AC-REFUND-003/004 | Fixed. Match on the echoed client reference, accept a sole entry, otherwise carry nothing and verify through `?reference=`. Fake now reproduces the captured shape, including the ambiguous case. |
| Should-fix | `V19` | The reference is derivable, so the column is redundant by construction. It is kept because an already-submitted refund must stay reconcilable even if the derivation changes. | T500 wording ("persist") | Accepted; backfill reproduces the same values. |

### Setup checks

- [x] Nullable SQL parameters cast/typed — the new column is written non-null from Java; the backfill uses `||` on a non-null `id`.
- [x] Read-check-then-write locks — unchanged; the reference is written at insert, inside the existing transaction.
- [x] A criterion is not marked covered while a phase is pending — traceability 112 defined / 108 active / 4 pending / 0 missing.
- [x] Unique constraints match cardinality — one client reference per refund, enforced by `refunds_client_reference_key`.
- [x] OpenAPI matches behaviour — `RefundView.clientReference` added as required.
- [x] No external I/O while holding a row lock — the provider call is still after commit.
- [x] One-time codes and message bodies redacted — no new logging; the reference is not a secret.
- [x] Every state-transition side effect has code or a named task — unchanged.

### Verification

- `JAVA_HOME=… ./gradlew check` — green (includes `spotlessCheck`, full suite, coverage gates).
- `python3 tools/check_traceability.py` — 112 / 108 / 4 / 0, OK.
- `npx @stoplight/spectral-cli lint specs/api/openapi.yaml` — no errors.
- `cd web && npm run build && npx tsc --noEmit && npm run lint` — green.
- New tests: `RefundReferencesTest`, live-shaped duplicate + ambiguous duplicate in
  `PaystackRefundProviderTest`, and `RefundRequestIT` asserts the persisted value equals the reference
  actually POSTed.

### Follow-ups

- T494 still gates provider enablement; T499 still needs rewriting.
- `RefundVerificationIT` failed once in a full-package run at `attempts == 1` and passed on rerun and in
  isolation. The assertion compares `next_verification_at` against wall-clock `Instant.now()`, so it is
  timing-sensitive; it is unrelated to T500 and worth a separate hardening task.

