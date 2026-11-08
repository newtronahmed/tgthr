# ADR 0009: SMS OTP provider through an adapter

- Status: Accepted
- Date: 2026-11-06
- Deciders: Ahmed Zubairu
- Constitution articles touched: VI, VII, VIII

## Context

Organizer sign-in needs a one-time code delivered to a Ghanaian phone. Email is a poor fit for the audience and WhatsApp requires a Meta business app and templates. SMS is familiar, works on any handset and does not need an install. An SMS gateway is a new external service, so it needs this ADR before implementation.

## Decision

We will deliver codes through an `SmsSender` adapter. Tests use an in-memory fake; production uses a Ghanaian SMS gateway selected by configuration. The recommended default is **Arkesel** (Ghana coverage, pay-as-you-go, simple HTTP API); Hubtel and mNotify are the fallbacks. No test calls the real gateway, and no provider credential is committed.

## Consequences

**Good**

- Works on any phone without an app, in WhatsApp's in-app browser or otherwise.
- A vendor change is configuration plus one adapter, not a domain change.

**Costs and risks**

- Per-message cost and SMS pumping abuse; rate limits per phone/IP and spend alerts are required.
- Deliverability varies by network; failures must return 503 and be alerted.
- Accepted on 2026-11-07 on the maintainer's instruction to proceed. The live sandbox check, vendor terms and credentials are still required before production; the production adapter is T162.

## Alternatives considered

| Option | Why not |
| --- | --- |
| WhatsApp Cloud API | Needs a Meta app, verified business and message templates; heavier for the first slice |
| Email OTP | Poor fit for the audience and often unavailable on shared devices |
| No OTP (magic link only) | Links leak through forwards and shared chats more easily than a short code |
