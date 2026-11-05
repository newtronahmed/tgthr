# ADR 0006: Paystack as payment partner

- Status: Superseded in part by ADR 0007 (platform service fee)
- Date: 2026-11-05
- Deciders: Ahmed Zubairu
- Constitution articles touched: I, V, VII, VIII

## Context

Collections and payouts move real money to and from mobile-money wallets in Ghana. We need a licensed partner that supports MTN, Telecel and AT wallets, signed callbacks, status lookup and payouts, without building a separate integration with each network.

Paystack publishes a Ghana rate of 1.95% for local transactions (cards, mobile money and bank transfer), settles the remainder on the next working day, and charges GHS 1 per transfer to a mobile-money wallet or GHS 8 per transfer to a bank account. The owner chose Paystack for both collections and payouts.

## Decision

1. **Provider.** Use Paystack Ghana for collections and payouts. All Paystack calls live in the `payments` module; no other module talks to Paystack.
2. **Collection fee.** The contributor bears the fee. The fee is 1.95% of the total charged, Paystack's published Ghana local rate at the date of this ADR. The rate is configuration in basis points, not a hardcoded constant. The total is grossed up so the goal pool receives the full contribution:
   - `totalPesewas = ceil(amountPesewas * 10000 / (10000 - feeBps))`
   - `feePesewas = totalPesewas - amountPesewas`
   - Example: a GHS 10.00 contribution (1,000 pesewas) is charged as GHS 10.20; the GHS 0.20 fee goes to Paystack and the goal pool receives GHS 10.00.
3. **Settlement.** Paystack deducts transaction fees per transaction and settles the remainder (net settlement), next working day. The ledger posts the FEE entry at confirmation and clears it when Paystack settles net. No separate fee invoice is expected.
4. **Payout fee.** The destination bears the fee. Paystack Ghana charges GHS 1 per mobile-money transfer and GHS 8 per bank transfer. The transfer amount is the payout amount minus the fee, so the goal pool is debited only the payout amount. Paystack requires a minimum transfer of GHS 10; a payout must leave a transfer amount of at least GHS 10 after the fee.
5. **Callback.** Paystack signs callbacks with `x-paystack-signature`, an HMAC-SHA512 of the raw request body using the secret key. The `payments` module verifies it before processing and maps the Paystack payload to the internal `ProviderCallback`. `specs/api/openapi.yaml` must be updated to the Paystack header and payload in the same pull request as the adapter.
6. **Idempotency.** The Paystack reference is the provider reference. ADR 0004's unique constraints apply; a repeated callback is accepted and ignored after the first.
7. **Secrets.** `PAYSTACK_SECRET_KEY` and any webhook secret come from the environment. They are never committed or logged.

## Ledger posting sets

| Event | Debit | Credit |
| --- | --- | --- |
| Contribution of A with fee F | Partner held A + F | Goal pool A; FEE F |
| Settlement of a contribution | Bank A; FEE F | Partner held A + F |
| Payout of P with fee G | Goal pool P | Partner held P − G; PAYOUT_FEE G |
| Settlement of a payout | PAYOUT_FEE G | Partner held G |

Any difference between F and the fee Paystack actually retains is an ADJUSTMENT with a reason (INV-LEDGER-002), never an edit.

## Consequences

**Good**

- One partner covers MoMo collections, cards and payouts.
- Published pricing and net settlement make reconciliation simpler.
- The fee is passed to the contributor, so the goal receives the amount the guest intended.

**Costs and risks**

- Paystack's published rate can change; it is configuration and must be reviewed before launch.
- The 1.95% figure must be confirmed as VAT-inclusive for Ghana; if VAT applies on top, the gross-up changes.
- Paystack settles on the next working day, so the goal pool is a receivable until settlement.
- The provider can freeze or delay settlement; reconciliation alerts from FEAT-001 and the operator runbook are the mitigation.
- The current webhook placeholder (`X-Signature`, `ProviderCallback`) is not Paystack-shaped and must be replaced.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Direct MTN, Telecel and AT integrations | Three contracts, three callback formats and three settlement reports before launch |
| Flutterwave | Higher published Ghana rates for this flow and no advantage for MoMo-first collections |
| Manual MoMo transfers with screenshot reconciliation | No signed callbacks, no automated confirmation, high fraud and reconciliation cost |
