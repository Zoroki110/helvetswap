# ClearportX Working Flow (DevNet Phase 3.x)

## Architecture
### Backend
- Lives in `backend/` (Spring Boot). REST controllers return `Result<T, DomainError>` and expose the AMM plus DevNet debug helpers.
- Auth: wallet-issued HMAC JWTs validated by the resource server; party is derived from JWT (or `X-Party` for some debug calls).
- Key controllers/endpoints (current flow): `/api/pools`, `/api/wallet/tokens/{partyId}`, `/api/auth/challenge`, `/api/auth/verify`, `/api/clearportx/debug/showcase/reset-pool`, `/api/clearportx/debug/add-liquidity-by-cid`, `/api/clearportx/debug/swap-by-cid`, `/api/debug/resolve-and-grant`.

### Frontend
- Active app is `quickstart/frontend` (React/TypeScript, Netlify). Axios client attaches `Authorization: Bearer <jwt>` when a wallet is connected and `X-Party` for endpoints that still expect it.
- Core surfaces: Swap page, Liquidity page, and the “Connect Wallet” dropdown (Dev / Loop / Zoro connectors).

### Ledger
- Pools and tokens live on-ledger via DAML templates (no template changes in this phase); the backend mediates ledger commands/queries.
- The showcase pool `cc-cbtc-showcase` lives under `ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37` and is reset through the debug endpoint.

## Auth & Wallet Flow
- Connect (Dev/Loop/Zoro) via `IWalletConnector` orchestrated by `WalletManager`.
- Challenge → verify:
  1) UI calls `POST /api/auth/challenge` with `partyId`; backend validates the party and stores a short-lived challenge.
  2) Wallet signs the challenge string.
  3) UI calls `POST /api/auth/verify` with `challengeId`, `partyId`, `signature`, `walletType`.
  4) Backend verifies and issues a HMAC JWT with claims: `sub` (party), `wallet`, `iss`, `iat`, `exp`.
- Storage/rehydration: JWT + party stored in-memory and `localStorage["clearportx.wallet.session"]`; on refresh the session is rehydrated and Axios automatically sends `Authorization: Bearer …` (and `X-Party` when needed).
- Controller usage of JWT: Liquidity (including debug add-liquidity-by-cid) resolves provider party from JWT (with optional `X-Party` fallback). Swap debug resolves trader party from JWT and uses that party’s ACS. Some debug endpoints still accept `X-Party` for operators, but the wallet flow prefers JWT.

## Core User Flows (current)
### View pools
- `/api/pools` aggregates pools from the app-provider directory plus PQS fallback and normalizes/sorts them. The canonical test pool is `cc-cbtc-showcase`. Pools stay visible when logged out; when logged in the same endpoint is called with JWT/party headers.

### View balances
- Balances use Token.Token via `/api/wallet/tokens/{partyId}` (JWT-backed). The `useLegacyBalances` hook aggregates amounts by symbol (CBTC/CC/ETH/etc) and feeds both Swap and Liquidity screens.

### Add liquidity (DevNet debug path)
1) Reset the showcase pool via `POST /api/clearportx/debug/showcase/reset-pool` with operator/provider set to the wallet party (JWT or `X-Party`).
2) UI resolves the pool CID, then calls `POST /api/clearportx/debug/add-liquidity-by-cid` with JWT (`X-Party` also sent).
3) Backend delegates to the real `AddLiquidityService`, returning `lpAmount`, `newPoolCid`, and updated reserves.
4) UI shows “Liquidity added – LP <amount> …”, refreshes balances/transactions; when logged out CTAs stay disabled.

### Swap (DevNet debug path)
1) UI resolves the pool CID for the selected pair (e.g., CBTC/CC) via `/api/debug/resolve-and-grant` when needed.
2) UI computes a local quote from pool reserves (slippage/price impact shown client-side).
3) On Swap, UI calls `POST /api/clearportx/debug/swap-by-cid` with `poolCid`, `poolId`, `inputSymbol`, `outputSymbol`, `amountIn`, `minOutput`; headers: `Authorization: Bearer <jwt>` + `X-Party` from the wallet.
4) Backend executes using the trader’s ACS and returns `resolvedOutput`, `newPoolCid`, etc. UI shows “Swap successful! Received <resolvedOutput> …” and reloads balances/transactions.

### Session behaviour
- Not connected: pools visible; balances show 0.0000; CTAs prompt “Connect Wallet”.
- Connected: session saved, balances fetched, CTAs enabled.
- Refresh: session rehydrates from localStorage, JWT reused until expiry; no manual reconnect needed.

## Quick Sanity Checklist
- Health:
  - `curl -s -w "\n%{http_code}\n" http://localhost:8080/actuator/health`
- Pools:
  - `curl -s -w "\n%{http_code}\n" http://localhost:8080/api/pools`
- Challenge/verify (replace PARTY and SIGNATURE):
  - `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/api/auth/challenge -H "Content-Type: application/json" -d '{"partyId":"$PARTY"}'`
  - `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/api/auth/verify -H "Content-Type: application/json" -d '{"challengeId":"...","partyId":"$PARTY","signature":"<SIGNATURE>","walletType":"dev"}'`
- Wallet tokens:
  - `curl -s -w "\n%{http_code}\n" http://localhost:8080/api/wallet/tokens/$PARTY -H "Authorization: Bearer $JWT"`
- End-to-end (DevNet debug, set PARTY and JWT):
  - Reset: `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/api/clearportx/debug/showcase/reset-pool -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" -H "X-Party: $PARTY" -d '{"operatorParty":"'$PARTY'","providerParty":"'$PARTY'","poolId":"cc-cbtc-showcase","cbtcAmount":"5","ccAmount":"4500000","archiveExisting":true}'`
  - Add liquidity: `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/api/clearportx/debug/add-liquidity-by-cid -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" -H "X-Party: $PARTY" -d '{"poolId":"cc-cbtc-showcase","amountA":"1000","amountB":"0.001","inputSymbol":"CC","outputSymbol":"CBTC"}'`
  - Swap: `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/api/clearportx/debug/swap-by-cid -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" -H "X-Party: $PARTY" -d '{"poolCid":"<poolCid>","poolId":"cc-cbtc-showcase","inputSymbol":"CC","outputSymbol":"CBTC","amountIn":"1000.0000000000","minOutput":"0.0000000001"}'`


## Phase 4.2 – Standard holdings
- Backend exposes `/api/wallet/holdings/{partyId}` returning standard holdings (Amulet→CC, CBTC, etc.) via ledger interface views and token metadata.
- Frontend `useHoldings` calls this endpoint; the wallet dropdown shows up to 5 holdings (symbol + formatted amount) with “+ N more” when applicable.
- Symbol mapping handles standard instrument IDs (e.g., Amulet mapped to CC with display name “Canton Coin”); decimals default to 10 when absent.
- Logged out: no balances shown; logged in: balances appear inside the wallet dropdown beneath the party card.
- This read-only path coexists with legacy Token.Token flows (swap/add-liquidity debug) without changes and is ready to surface CBTC holdings as soon as they appear on DevNet.

- Loop connect verified (Phase 4.2): popup flow works, dropdown shows CC/CBTC balances, pools remain visible when logged out.

## Phase 4.3 – Holding pools (P0 skeleton)
- DAML: `AMM.HoldingPool` template (signatory operator) with status `Uninitialized|Active|Paused`, instruments A/B, feeBps, createdAt, and optional reserve holding CIDs. Choices: `SetPaused`, `SetActive`, `Bootstrap` (placeholder; records reserve CIDs and marks Active).
- Backend endpoints (operator = AppProvider party):
  - `POST /api/holding-pools` body `{"poolId","instrumentA":{"admin","id"},"instrumentB":{"admin","id"},"feeBps":<int>}` → creates HoldingPool, returns `{poolId, contractId, status}`.
  - `GET /api/holding-pools` → lists visible HoldingPool contracts (status/instruments/contractId).
  - `GET /api/holdings/{party}/utxos` → contract-level holdings via interface `#splice-api-token-holding-v1:Splice.Api.Token.HoldingV1:Holding` for any party (Loop/ClearportX). Fields: contractId, instrumentAdmin/id, amount, decimals, owner, observers (empty if not disclosed).
  - `POST /api/holding-pools/{poolId}/bootstrap` → 501 Not Implemented (shape locked; expects holdingCidA/holdingCidB/amounts/minLpOut in a future gate).
- Quick checks (replace PARTY/JWT):
  - `curl -s -w "\n%{http_code}\n" http://localhost:8080/api/holding-pools`
  - `curl -s -w "\n%{http_code}\n" -X POST http://localhost:8080/api/holding-pools -H "Content-Type: application/json" -d '{"poolId":"cc-cbtc-loop","instrumentA":{"admin":"ClearportX-DEX-1","id":"Amulet"},"instrumentB":{"admin":"ClearportX-DEX-1","id":"CBTC"},"feeBps":30}'`
  - `curl -s -w "\n%{http_code}\n" http://localhost:8080/api/holdings/$PARTY/utxos`
