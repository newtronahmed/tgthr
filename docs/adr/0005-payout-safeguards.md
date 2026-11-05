# ADR 0005: Payout safeguards: hold window and optional second approver

- Status: Accepted
- Date: 2026-11-04
- Deciders: Ahmed Zubairu
- Constitution articles touched: V

## Context

A payout moves a whole pool to one MoMo number, often an organizer's own. A fake or hijacked goal is the main fraud risk. The product owner decided that a second approver must not be mandatory for every goal.

## Decision

We will make the second approver optional per goal (off, above a threshold, or always), recommend "above GHS 500" as the default, and show the setting on the public goal page. Every payout waits in a hold window (proposed 60 minutes) during which the organizer can cancel it and contributors can report it. For an admin MoMo destination, the account name must match the admin's verified name. Changing a destination needs a fresh OTP and notifies contributors.

## Consequences

**Good**

- Small groups stay simple, and contributors can see the safeguard level before giving.
- The hold window and the name match protect even single-approver goals.

**Costs and risks**

- A single dishonest or compromised admin can still send a payout after the hold window; the operator freeze and contributor reports are the remaining defence.
- The hold window delays legitimate payouts; its length is configurable and to be confirmed.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Mandatory two approvers | Rejected by the product owner as too heavy for small groups |
| No hold window | Leaves no time to catch a hijacked account |
