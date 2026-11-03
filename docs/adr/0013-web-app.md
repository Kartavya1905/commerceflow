# ADR-0013: The web app

- Status: Accepted
- Date: 2026-11-02
- Refines: ROADMAP Milestone 8 decisions (React + TypeScript + Vite, generated client, token handling)

## Context
Milestone 8 adds the customer and admin web app. It must be functional rather than fancy, and its review gate names four defects to rule out: tokens in web storage, a new Idempotency-Key per click, polling without backoff, and admin pages that are only hidden.

## Decisions
1. **One origin.** The browser loads the app and calls the API on the same origin. In Compose, nginx (`web`, port 8000) serves the build and proxies `/api` to the gateway; in development, Vite does the same. The refresh cookie that identity sets (`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`) therefore just works, with no CORS and no cross-site requests to defend.
2. **Tokens.** The access token lives only in a module variable. A reload loses it, and one call to `/api/v1/auth/refresh` gets a new one from the cookie. ESLint forbids `localStorage` and `sessionStorage` in app code, and a unit test and the browser smoke test both check that web storage stays empty and that the cookie is invisible to page scripts. Concurrent 401s share one refresh, because identity revokes a whole token family when a rotated refresh token is reused.
3. **The client is generated from the contracts.** `openapi-typescript` turns `contracts/.../openapi/*.yaml` into types, and `openapi-fetch` is a small typed `fetch` wrapper over them. The generated files are committed, and CI regenerates them and fails on any difference. Writing the client surfaced two gaps between contract and code. The Milestone 7 dead-letter endpoints were missing from `order-service.yaml` (added). The payment contract's `POST /admin/payments/{id}/reconcile` was not implemented.
4. **Checkout is a state machine with one key per attempt.** The key is made when an attempt starts, and reused for every retry of that attempt (a double click, a timeout, a 5xx, a 429). It is dropped only when the attempt ends: the order was accepted, or the request was refused (4xx). The request is sent from an effect keyed by the attempt, so neither a click nor a re-render can send a second, different request. Component tests check three cases: three clicks send one order; a 503 retry reuses the key; a refusal gets a new one.
5. **Order status polls with exponential backoff and jitter** (1 s, 2 s, 4 s… capped at 15 s, each up to 25 % shorter). Polling stops when the order is final or the page is left.
6. **Admin pages are hidden, and the API is what refuses.** The UI shows admin pages to the ADMIN role only, from `/users/me`, never from decoding the token. The browser smoke test also calls admin endpoints with a customer's token and requires 403.
7. **nginx is the gateway's one trusted proxy.** Behind nginx, every visitor would reach the gateway from nginx's address and share one rate-limit bucket, login included. The gateway now believes `X-Forwarded-For` only from the hosts in `commerceflow.gateway.trusted-proxies` (Compose: `web`), and then only its right-most entry, the address nginx saw. From anyone else the header is ignored, so no client can pick its bucket.
8. **Strict headers.** nginx sends a CSP with no `'unsafe-inline'` (the build has no inline script or style), `frame-ancestors 'none'`, `nosniff`, `no-referrer`, and a restrictive `Permissions-Policy`. It runs as an unprivileged user.
9. **No Node on the machine.** npm, Vitest and the build run in a pinned `node:22.23.4-alpine3.24` image, and Playwright in `mcr.microsoft.com/playwright:v1.64.1-noble` on the host network, so the browser reaches the app as `localhost`, the one plain-HTTP origin where it keeps a `Secure` cookie. Library versions are pinned exactly, each the latest patch of a major line released at least two weeks earlier.

## Consequences
- Prices in the cart are display only. order-service prices the order from catalog when it is placed, and the page says so.
- A customer who changes the cart after a failed, retryable attempt sends a different body under the same key, and order-service answers 422 (`idempotency-key-reused`). The app treats that as the end of the attempt, and the next click gets a new key.
- Without the `web` container in front, the gateway behaves exactly as before. With an empty trusted-proxy list it believes no one.
