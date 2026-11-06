# Implementation plan: Guest contribution

| Field | Value |
| --- | --- |
| Spec | specs/features/001-contributions/spec.md |
| Status | Draft |
| Author | Ahmed Zubairu |
| Related | specs/domain/ledger.md, specs/domain/contribution-state-machine.md, docs/adr/0004-database-enforced-idempotency.md, docs/adr/0006-paystack-payment-partner.md, docs/adr/0007-platform-service-fee.md |

## Summary

Build FEAT-001 as a backend slice first: read a public goal, create a contribution, request a Paystack MoMo prompt, apply signed callbacks and the timeout poll, post the ledger entries, and expose status and receipt data. The main trade-off is a synchronous Paystack prompt request inside the create call, bounded by a short timeout and protected by a database idempotency key, so the guest receives PENDING immediately and the callback remains the source of truth. The fee engine, state machine and ledger are proven with unit and Testcontainers integration tests before the Next.js app is scaffolded.

## Constitution check

| Article | Does this plan comply? | Notes |
| --- | --- | --- |
| I. Money is exact and traceable | Yes | `Money` is integer pesewas; the fee calculator uses integer arithmetic; ledger entries are append-only and every posting set nets to zero; property tests cover fees; `ledger/` and `payments/` changes need a CODEOWNER review. |
| II. Spec first | Yes | FEAT-001 is Ready; `openapi.yaml` is updated before the adapter and callback handler are written. |
| III. Test first | Yes | Every criterion and invariant has a tagged test; the failing test is enabled before the implementation; the in-process fake Paystack server is the only provider used by automated tests; `Clock` and UUIDs are injected. |
| IV. Idempotent and consistent | Yes | Unique constraints on the client idempotency key, provider reference and posting set; callbacks apply through the state machine in one transaction with the ledger posting. |
| V. Trust is a feature | Yes | Public DTOs never carry a phone number; anonymous feed shows a masked name; a late success confirms with an alert; FEAT-001 has no payouts. |
| VI. Guest first, low bandwidth | Yes | Public endpoints need no auth or cookies; the web app is scaffolded after the backend contract is green; p95 targets are recorded in the spec. |
| VII. Simple until proven otherwise | Yes | One modular monolith; no new runtime dependency or service; the fake provider runs in-process in tests. |
| VIII. Security and privacy | Yes | Phone stored as AES-GCM ciphertext with a keyed HMAC lookup hash; Paystack signature verified before processing; rate limits per IP and MoMo number; secrets from the environment. |
| IX. Observable | Yes | Structured events with a correlation ID; Micrometer metrics; alerts for timeout, unmatched callback, amount mismatch, late success and reconciliation differences. |

## Technical context

- Modules touched: `goal` (public goal read), `contribution` (create, state machine, feed), `payments` (Paystack HTTP client, signature verification, status lookup), `ledger` (money, posting engine). `identity`, `payout` and `notification` are out of scope.
- New dependencies: none. Spring Web, JDBC, Flyway, Actuator, Testcontainers and jqwik are already present. The fake provider uses the JDK `HttpServer`; crypto uses the JDK JCA.
- Data stores: PostgreSQL. New tables: `goals`, `contributions`, `contribution_events`, `ledger_entries`. No stored balances.

## Design

### Domain and data

`goals`: `id`, `slug` (unique), `title`, `template`, `target_type`, `fixed_amount_pesewas`, `target_amount_pesewas`, `minimum_amount_pesewas` (default 1,000), `deadline`, `status`, `destination_type`, `second_approver`, `hold_window_minutes`, `surplus_rule`, timestamps.

`contributions`: `id` (UUID), `goal_id`, `amount_pesewas`, `processing_fee_pesewas`, `platform_fee_pesewas`, `total_pesewas`, `state`, `provider_reference` (unique), `payer_phone_ciphertext`, `payer_phone_hash`, `display_name`, `anonymous`, `idempotency_key` (unique), timestamps, `confirmed_at`.

`contribution_events`: append-only history with `contribution_id`, `from_state`, `to_state`, `event`, `occurred_at`, `detail` JSONB.

`ledger_entries`: `id`, `posting_set_id` (the contribution id for a contribution event), `contribution_id`, `account`, `entry_type`, `direction`, `amount_pesewas`, `currency`, `created_at`. A unique constraint on `(posting_set_id, entry_type, account, direction)` makes a repeated callback a no-op. A deferred constraint trigger verifies at commit that every posting set nets to zero. A trigger rejects `UPDATE` and `DELETE` on `ledger_entries` and `contribution_events`; the application role is also granted only `SELECT`/`INSERT` on those tables in production.

### Fee engine

A pure `FeeCalculator` in the `contribution` module, using `ledger.Money`:

1. `platformFeePesewas = ceil(amountPesewas * platformFeeBps / 10000)`
2. `grossBasePesewas = amountPesewas + platformFeePesewas`
3. `totalPesewas = ceil(grossBasePesewas * 10000 / (10000 - paystackFeeBps))`
4. `processingFeePesewas = totalPesewas - grossBasePesewas`

Defaults: `platformFeeBps = 150`, `paystackFeeBps = 195`. The rates are configuration, not constants. The goal amount and platform amount stay whole; the contributor bears both fees. A property test checks `total = amount + processing + platform` and that the corresponding ledger set nets to zero.

### State machine

`ContributionState` and `ContributionEvent` enums with a single transition table from the domain spec. `apply(event)` returns a new state or records an illegal transition and leaves the state unchanged. Every legal transition appends a `contribution_events` row in the same transaction. The two late-success transitions (`EXPIRED → CONFIRMED`, `FAILED → CONFIRMED`) raise an alert.

### Payments

`payments` owns the Paystack adapter:

- `PaystackClient.initiateCharge(reference, totalPesewas, momoNumber, network)` calls Paystack's charge API and returns the provider reference.
- `PaystackClient.verify(reference)` returns the final status for the timeout job.
- `PaystackSignature.verify(rawBody, xPaystackSignature)` uses HMAC-SHA512 with the secret key.
- `PaystackWebhookMapper` maps `charge.success` to the internal `PaymentResult` (reference, SUCCESS/FAILED, amount, occurredAt).
- The callback endpoint stays `POST /webhooks/momo`; `openapi.yaml` is updated to the `x-paystack-signature` header and Paystack payload before the adapter is implemented.

The automated tests use an in-process `FakePaystackServer` built on the JDK `HttpServer`, with deterministic scenarios selected by the payer number or an explicit header: success, declined, timeout, duplicate, late, bad signature, wrong amount, request rejected, status lookup. `tools/momo-fake` remains the manual development service; its contract mirrors `ProviderCallback` until it is replaced by the Paystack sandbox for manual checks.

### API

- `GET /public/goals/{slug}`: goal view for the public page, including `payoutRules`; no phone numbers.
- `POST /public/goals/{slug}/contributions`: requires `Idempotency-Key`; validates goal state and amount; computes fees; inserts the contribution and calls Paystack; returns 202 with `ContributionStatus`.
- `GET /public/contributions/{id}`: status, fee breakdown and receipt when CONFIRMED.
- `POST /webhooks/momo`: raw body, `x-paystack-signature`, 204 on success or duplicate, 401 on invalid signature.

`ContributionStatus` carries `amountPesewas`, `processingFeePesewas`, `platformFeePesewas`, `feePesewas` (compatibility sum) and `totalPesewas`.

### Sequence for the main path

1. `GET /public/goals/{slug}` reads the goal and returns the public DTO.
2. `POST /public/goals/{slug}/contributions` checks the goal is open and the amount is allowed.
3. `FeeCalculator` computes the platform fee, processing fee and total.
4. In one transaction, insert the contribution in INITIATED, insert the history row and reserve the idempotency key. A unique violation returns the existing contribution without a second prompt.
5. Call Paystack `initiateCharge` with the contribution id as the reference. On acceptance, update to PENDING and append history. On rejection or timeout, update to FAILED and append history; the response still carries the contribution state.
6. Paystack calls `POST /webhooks/momo`. Verify the signature, load the contribution by reference, apply the state machine and, for SUCCESS, post CONTRIBUTION, FEE and PLATFORM_FEE in the same transaction.
7. `GET /public/contributions/{id}` returns the state; once CONFIRMED it includes the receipt data.
8. The timeout job runs every minute, locks due PENDING contributions with `FOR UPDATE SKIP LOCKED`, calls `verify`, and applies CONFIRMED or EXPIRED. A late SUCCESS confirms with an alert.

## Test strategy

| Criterion | Test level | Test class or file | Notes |
| --- | --- | --- | --- |
| AC-CONTRIB-001 | Integration | `ContributionApiIT` | Real HTTP + Testcontainers Postgres + fake Paystack; assert PENDING and one prompt |
| AC-CONTRIB-002 | Integration | `ContributionApiIT` | Fixed goal, wrong amount, 422 and no row |
| AC-CONTRIB-003 | Unit | `GoalRulesTest` | Open-goal minimum 1,000 pesewas |
| AC-CONTRIB-004 | Integration | `ContributionCallbackIT` | Duplicate SUCCESS; one posting set |
| AC-CONTRIB-005 | Integration | `ContributionCallbackIT` | One transaction; balanced entries; goal total rises |
| AC-CONTRIB-006 | Integration | `ContributionCallbackIT` | FAILED callback leaves ledger empty |
| AC-CONTRIB-007 | Integration | `ContributionCallbackIT` | Invalid signature, 401, no state change |
| AC-CONTRIB-008 | Integration | `ContributionStatusIT` | Clock advanced 5 minutes; poll applies final status |
| AC-CONTRIB-009 | Integration | `ContributionApiIT` | New contribution after FAILED/EXPIRED; old row unchanged |
| AC-CONTRIB-010 | Contract | `PublicContractTest` | Anonymous feed hides the name; organizer view not in this slice |
| AC-CONTRIB-011 | Contract | `PublicContractTest` | No phone number in any public response |
| AC-CONTRIB-012 | Integration | `ContributionApiIT` | Closed, frozen and past-deadline goals return 409 |
| AC-CONTRIB-013 | Contract | `ContributionStatusIT` | Receipt only when CONFIRMED; fee breakdown present |
| AC-CONTRIB-014 | Unit | `FeeCalculatorTest` | jqwik property over the allowed range; total identity and ledger net zero |
| AC-CONTRIB-015 | Integration | `ContributionApiIT` | Same Idempotency-Key returns the original and sends one prompt |
| AC-CONTRIB-016 | Integration | `ContributionCallbackIT` | Late SUCCESS after EXPIRED/FAILED confirms once and alerts |
| AC-CONTRIB-017 | Unit | `FeeCalculatorTest` | 1.5% rounded up, charged on top, goal amount unchanged |
| AC-CONTRIB-018 | Integration | `ContributionCallbackIT` | SUCCESS before PENDING confirms once; one posting set |
| AC-CONTRIB-019 | Integration | `ContributionApiIT` | Fake rejection/timeout → FAILED, no ledger |
| AC-CONTRIB-020 | Integration | `ContributionCallbackIT` | Unknown reference → alert, no state change |
| AC-CONTRIB-021 | Integration | `ContributionCallbackIT` | Wrong amount → alert, no state change |
| INV-LEDGER-001 | Integration | `LedgerPostingIT` | Every posting set nets to zero; deferred trigger catches violations |
| INV-LEDGER-002 | Integration | `LedgerPostingIT` | UPDATE/DELETE on ledger entries rejected |
| INV-LEDGER-003 | Integration | `LedgerPostingIT` | Deferred to FEAT-002 (payout); existing disabled test stays |
| INV-LEDGER-004 | Integration | `LedgerPostingIT` | Duplicate provider reference / idempotency key rejected by the database |
| INV-LEDGER-005 | Unit | `MoneyTest` | Existing tests; no floating point |
| INV-LEDGER-006 | Integration | `LedgerPostingIT` | No balance column; balances derived from entries |
| INV-STATE-001 | Unit | `ContributionStateMachineTest` | Only listed transitions change state |
| INV-STATE-002 | Integration | `ContributionCallbackIT` | Ledger entries exist only for CONFIRMED contributions |
| INV-STATE-003 | Unit | `ContributionStateMachineTest` | Every legal transition appends history; history is append-only |

## Migrations

- `V1__goal_and_contribution.sql`: goals, contributions, contribution_events, indexes and unique constraints.
- `V2__ledger.sql`: ledger_entries, posting-set balance trigger and append-only trigger.
- Scripts are forward-only and backward compatible with the running version: yes (additive tables only).
- The first migration is written with the first failing integration test, not before.

## Rollout

- Config: `PAYSTACK_SECRET_KEY`, `PAYSTACK_BASE_URL`, `PAYER_PHONE_KEY`, `PLATFORM_FEE_BPS=150`, `PAYSTACK_FEE_BPS=195`, `CONTRIBUTION_TIMEOUT=PT5M`, `RATE_LIMIT_ENABLED=true`.
- Feature flag: none; the endpoints are new. The web app is deployed after the backend slice is green.
- Observability: contribution created, prompt requested, callback received/rejected, state transition, timeout, late success; metrics for contributions by state, callback latency, PENDING-to-CONFIRMED time and platform fees collected; alerts for timeout, unmatched callback, amount mismatch, late success and reconciliation.
- Rollback: revert the application deployment; migrations are additive and forward-only. A failed prompt request leaves a FAILED contribution, never a stuck PENDING.

## Risks

| Risk | Mitigation |
| --- | --- |
| Paystack onboarding or sandbox delays | Build and test against the in-process fake; swap the base URL and secret for sandbox once available |
| Fee rounding differs from Paystack | Gross up with the documented formula; reconcile the retained fee and post an ADJUSTMENT for any difference |
| Webhook signature or payload mismatch | Update `openapi.yaml` before the adapter; contract-test the Paystack shape; reject invalid signatures with 401 |
| Phone encryption key loss or rotation | `PAYER_PHONE_KEY` from the environment; document rotation; keyed hash changes with the key, so plan a re-encryption task before launch |
| Testcontainers requires Docker in CI | CI already runs on Ubuntu with Docker; keep the integration tests in the required backend job |
| Missing Gradle wrapper / JDK 21 | Generate and commit the wrapper; install/select JDK 21; run `make check` before the first slice |
| Single database role in local dev | Append-only triggers enforce immutability; production uses a separate `SELECT`/`INSERT` application role |
| Timeout job duplicates | `FOR UPDATE SKIP LOCKED`; idempotent state transition and ledger posting |
| Late success after EXPIRED/FAILED | Confirm with an alert; never drop a confirmed payment |

## Complexity tracking

No Constitution violations. The append-only trigger is defense in depth on top of the production role grants required by ADR 0002; it does not replace them.
