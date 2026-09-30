# HelvetSwap MainNet Access Gate

Password-gated "Coming Soon" wall for the MainNet Vercel project (`helvetswap.app`). Enforced via Vercel Edge Middleware before the CRA bundle ever loads. Unauthenticated visitors see `public/gate.html`; a successful `POST /api/unlock` sets an HMAC-signed cookie that the middleware verifies on subsequent requests.

**Scope:** MainNet Vercel project only. DevNet projects must leave `NETWORK` unset or set it to something other than `mainnet` — the middleware is a no-op in that case.

## Required environment variables (Vercel dashboard)

Set all four on the MainNet project in both **Production** and **Preview** scopes. If any is missing at runtime the middleware fails closed (`503` with header `Vercel-Edge-Misconfig: <var-name>`).

| Variable | Purpose | How to generate |
|---|---|---|
| `SITE_ACCESS_PASSWORD` | Shared unlock password, compared in constant time. | Pick a strong passphrase (≥16 chars). Distribute out-of-band (Signal). Never commit. |
| `SITE_ACCESS_SECRET` | HMAC-SHA256 signing key for the `hs_access` cookie. | `openssl rand -hex 32`. Never reuse across envs. |
| `UPSTASH_REDIS_REST_URL` | Upstash REST endpoint for the rate limiter. | Upstash console → database → REST API. Free tier is more than enough. |
| `UPSTASH_REDIS_REST_TOKEN` | Upstash REST auth token. | Same Upstash console page. |

Optional:

| Variable | Purpose |
|---|---|
| `NETWORK` | Set to `mainnet` on the MainNet project. Any other value (or unset) disables the gate for that project. |

## Rotation

- **Rotate `SITE_ACCESS_PASSWORD`**: update the Vercel env var, redeploy. Existing cookies still work (they only bind the password at unlock time). To force re-auth, rotate `SITE_ACCESS_SECRET` as well.
- **Rotate `SITE_ACCESS_SECRET`**: update the Vercel env var, redeploy. **All existing `hs_access` cookies immediately invalidate** — every user sees the gate again. This is the mechanism to force a global logout.
- **Rotate Upstash creds**: regenerate in the Upstash console, update both env vars, redeploy. Rate-limit counters reset.

## Files

- `middleware.ts` — Edge middleware, runs on every request. Allowlist + cookie verify + rewrite to `/gate.html`.
- `api/unlock.ts` — Edge function. `POST /api/unlock` with `{ "password": "…" }`, rate-limited, issues `hs_access` cookie on match.
- `api/_lib/crypto.ts` — Web Crypto HMAC sign/verify + constant-time string compare. No npm crypto deps.
- `api/_lib/ratelimit.ts` — `@upstash/ratelimit` wrapper, fixed window 5 / 15 min. Keyed on `cf-connecting-ip` (primary, since Cloudflare fronts `helvetswap.app`), falling back to `x-forwarded-for` → `x-real-ip` → `"unknown"`. Upstash client is lazy-initialised so a missing env var surfaces as a handled 503, not a cold-start crash.
- `public/gate.html` — Static teaser page, inline CSS/JS, no framework bundle. Served via middleware rewrite.
- `public/gate-assets/` — Logo + favicon used only by the gate, kept separate from the CRA bundle.
- `vercel.json` — Edge runtime for `api/unlock.ts`, `no-store` + `noindex` headers for `/gate.html`.

## Unlock endpoint behaviour

- `POST /api/unlock` with a non-string `password` field (number, object, `null`, missing) returns **401 `invalid-password`**, not 400. The constant-time compare coerces non-string to `''` and runs unconditionally. This avoids exposing a distinct "payload shape wrong" status from "password wrong" — no input-shape oracle.
- **400** is reserved for un-parseable JSON bodies (e.g. empty body, malformed JSON) — structurally invalid, before the password field is ever inspected.
- **429** on rate-limit exceed, with `Retry-After` header. Body does not reveal remaining-attempts count.
- **503** with `Vercel-Edge-Misconfig: <var>` header for any missing required env var — fail-closed.

## Cookie details

- Name: `hs_access`
- Value: `<base64url(JSON payload)>.<base64url(HMAC-SHA256 signature)>` where payload is `{ iat, exp }` (both unix seconds).
- Attributes: `HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=2592000` (30 days).
- `Domain` is deliberately **not** set — the cookie is host-scoped (does not leak to other `helvetswap.app` subdomains).

## Verifying the gate post-deploy

```sh
# 1. Fresh visit: expect gate HTML
curl -si https://helvetswap.app/ | head -20
# Expect: 200 OK, body is gate.html, Cache-Control: no-store

# 2. Wrong password: 401
curl -si -X POST https://helvetswap.app/api/unlock \
  -H 'content-type: application/json' \
  -d '{"password":"wrong"}'
# Expect: 401, body {"ok":false,"error":"invalid-password"}

# 3. Correct password: 200 + Set-Cookie
curl -si -X POST https://helvetswap.app/api/unlock \
  -H 'content-type: application/json' \
  -d "{\"password\":\"$SITE_ACCESS_PASSWORD\"}"
# Expect: 200, Set-Cookie: hs_access=...; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=2592000

# 4. Reuse cookie: real app
curl -si -c /tmp/gate.jar -X POST https://helvetswap.app/api/unlock \
  -H 'content-type: application/json' \
  -d "{\"password\":\"$SITE_ACCESS_PASSWORD\"}" > /dev/null
curl -si -b /tmp/gate.jar https://helvetswap.app/ | head -20
# Expect: real CRA index.html
```

## Removing the gate (launch day)

Three options, in order of least disruption:

1. **Stop enforcing**: set `NETWORK` to something other than `mainnet` on the MainNet project, redeploy. Middleware becomes a no-op. Fastest rollback, everything else stays in place.
2. **Rip it out**: delete `middleware.ts`, `api/unlock.ts`, `api/_lib/`, `public/gate.html`, `public/gate-assets/`; remove `functions` block from `vercel.json`; drop `@upstash/ratelimit`, `@upstash/redis`, `@vercel/edge` from `package.json`; delete unused Vercel env vars.
3. **Keep it ready for emergencies**: leave all code in place but clear `SITE_ACCESS_PASSWORD` — the middleware will fail closed on any unauthenticated visit (503 misconfig). Only useful if you want a one-env-var kill switch; otherwise prefer option 1.
