# ADR 0007: Platform service fee

- Status: Accepted
- Date: 2026-11-05
- Deciders: Ahmed Zubairu
- Constitution articles touched: I, V, VII
- Supersedes: ADR 0006 in part (the total-charged formula and posting sets)

## Context

The platform must earn revenue. Paystack's 1.95% collection fee is a pass-through cost, not platform income. The owner chose a 1.5% platform service fee paid by the contributor on top of the contribution, so the goal still receives the full amount the guest intended.

## Decision

1. **Fee.** Add a platform service fee of **1.5% of the contribution amount**, rounded up to the nearest pesewa. The contributor bears it. It is charged on top of the contribution and does not reduce the goal amount.
2. **Total charged.** Paystack charges its 1.95% processing fee on the whole transaction, including the platform fee. Gross up over both so the goal and the platform each receive their full amount:
   - `platformFeePesewas = ceil(amountPesewas * 150 / 10000)`
   - `grossBasePesewas = amountPesewas + platformFeePesewas`
   - `totalPesewas = ceil(grossBasePesewas * 10000 / 9805)`
   - `processingFeePesewas = totalPesewas - grossBasePesewas`
   - Example: a GHS 10.00 contribution (1,000 pesewas) has a 15-pesewa platform fee; the total charged is 1,036 pesewas (GHS 10.36), of which 21 pesewas is Paystack's fee and 1,000 pesewas reaches the goal.
3. **Disclosure.** The checkout shows the contribution, processing fee, platform fee and total before the prompt is requested. The receipt repeats the breakdown. The public goal page states that a 1.5% platform fee is added at checkout.
4. **Ledger.** A new entry type `PLATFORM_FEE` records platform revenue. It is credited at confirmation and is not a clearing account. The posting sets are below.
5. **Refunds.** Whether the platform fee is refunded with a reversed contribution is out of scope for FEAT-001 and will be specified in FEAT-005. Until then a refund reverses the contribution and fee entries only as FEAT-005 defines.
6. **Tax.** Platform revenue may attract VAT or other levies. The treatment must be confirmed with counsel before the first live payment (Constitution VIII.4).

## Ledger posting sets

| Event | Debit | Credit |
| --- | --- | --- |
| Contribution of A with platform fee P and processing fee F | Partner held A + P + F | Goal pool A; PLATFORM_FEE P; FEE F |
| Settlement of a contribution | Bank A + P; FEE F | Partner held A + P + F |
| Payout of X with payout fee G | Goal pool X | Partner held X − G; PAYOUT_FEE G |
| Settlement of a payout | PAYOUT_FEE G | Partner held G |

`FEE` and `PAYOUT_FEE` clear when the partner retains or deducts the charge. `PLATFORM_FEE` remains as revenue. Any difference between the calculated fee and what Paystack actually retains is an ADJUSTMENT with a reason (INV-LEDGER-002).

## Consequences

**Good**

- The platform earns 1.5% of every confirmed contribution without reducing the goal amount.
- The fee is transparent and scales with usage; no organizer billing or subscription infrastructure is required.
- The gross-up keeps the goal and platform amounts whole despite Paystack's percentage fee.

**Costs and risks**

- A GHS 10.00 contribution now costs the guest GHS 10.36 (3.6% in total fees), which may affect conversion; the checkout must make the breakdown clear.
- The platform fee is charged on contributions only, not payouts; the revenue is proportional to collection volume.
- Refund treatment and tax are unresolved until FEAT-005 and counsel respectively.
- A rounded-up 1.5% gives small contributions a slightly higher effective rate; acceptable at the GHS 10 minimum.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Deduct 1.5% from the contribution | The goal receives less than the guest intended; poor trust |
| Organizer subscription | Needs accounts, recurring billing and churn management before launch |
| Payout markup | Charges only when money leaves and may hit the beneficiary; less transparent |
| Markup on Paystack's fee | Hides the platform's margin inside a pass-through cost |
