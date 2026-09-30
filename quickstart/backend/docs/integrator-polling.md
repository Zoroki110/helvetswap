# Integrator polling cadence

## Overview

After submitting a swap TI (signed-TI submission to the ledger), clients detect the
pending→completed transition by polling `/api/swap/inspect/{requestId}`. The backend
`SwapPollerService` picks up pending TIs from the ledger and hands them to
`SwapTiProcessorService`, typically completing 3–10 seconds after submission.

## Recommended client cadence

Use exponential backoff:

- Start: 2 seconds between polls.
- Doubling: 2s → 4s → 8s.
- Cap: 8 seconds maximum between polls.
- Reset: on any HTTP 200 response, drop back to the initial 2s.
- Horizon: after ~30 seconds without a `completed` result, surface a user-visible
  "still pending" state to the end user. Do not continue polling indefinitely.

Do NOT poll at a fixed sub-2-second cadence. The `/api/swap/inspect` route shares
the backend's rate-limit bucket with other swap-path endpoints and will 429 during
`SwapPollerService`'s own ledger-read windows.

## Rate-limit behavior

`/api/swap/inspect` shares the global token bucket (see `RateLimiterConfig.java`).
When exceeded, the backend responds:

- Status: HTTP 429 Too Many Requests
- Header: `Retry-After: <seconds>` — RFC 7231 §7.1.3 delta-seconds, integer, always ≥ 1.
- JSON body:

        {
          "code": "RATE_LIMIT_EXCEEDED",
          "message": "Rate limit exceeded for devnet compliance",
          "status": 429,
          "retryAfter": <seconds>,
          "path": "...",
          "requestId": "..."    // optional, echoed from X-Request-ID
        }

The `Retry-After` header and body `retryAfter` field are always equal. Clients SHOULD
honor the header. Generic exponential backoff (as described above) is an acceptable
fallback if the client's HTTP stack does not parse `Retry-After`.

## What NOT to do

- Fixed 1-second or 1.5-second polling loops. Observed to trip the rate limiter during
  the poller's own ledger-read window (a DevNet integrator hit this during an E2E
  bring-up; the fix was switching to a 2s → 8s exponential backoff with reset-on-200).
- Ignoring 429 responses and continuing to hammer. The bucket does not reset on
  request arrival; hammering extends the wait.
- Polling without a horizon. If a swap has not completed after 30s, raise a pending
  state in the UI; a ledger-side failure may require re-submission rather than
  further polling.

## Memo / TI schema

Memo fields for swap TI submission (`v`, `requestId`, `poolCid`, `direction`,
`minOut`, `receiverParty`, `deadline`) are defined in the source of truth:
`SwapPollerService.java` and `SwapTiProcessorService.java`. Refer to those files
directly when constructing a valid TI memo — this document intentionally does not
duplicate that schema.

## Wire-shape notes

- Public endpoint `/api/holding-pools/public` returns pool state with internal field
  names: `reserveAmountA`, `reserveAmountB`, `feeBps`. Integrators should parse these
  directly or map them client-side.
- Private endpoint `/api/integrator/holding-pools` returns the same shape plus
  `poolCid` for state-sync purposes.
- If a future integrator needs a `reserveA / reserveB / feeRate` alias shape on
  either endpoint, raise on Signal — adding server-side aliases is one line per
  field but requires alignment before shipping.
