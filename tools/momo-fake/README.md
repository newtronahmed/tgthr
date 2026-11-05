# MoMo fake provider

A small local service that stands in for the payment partner so no automated test ever calls the real one (Constitution III.3). It is not built yet; build it test first alongside FEAT-001.

## Scenarios it must support

Each scenario is selected by the payer number or an explicit header, so tests are deterministic.

| Scenario | Behavior |
| --- | --- |
| Success | Accepts the prompt request, then sends a signed SUCCESS callback |
| Declined | Accepts the request, then sends a signed FAILED callback |
| Timeout | Accepts the request and never calls back |
| Duplicate | Sends the same SUCCESS callback twice |
| Late | Sends SUCCESS after the timeout window |
| Out of order | Sends a FAILED callback after a SUCCESS for the same reference |
| Bad signature | Sends a callback with an invalid signature |
| Wrong amount | Sends SUCCESS with an amount different from the request |
| Request rejected | Rejects the prompt request immediately |
| Status lookup | Answers the polling fallback with the true final status |

## Contract

The request and callback shapes follow the real partner's API once onboarding gives us its documentation. Until then, mirror `ProviderCallback` in `specs/api/openapi.yaml`.
