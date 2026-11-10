# API reference

The contracts are the source of truth: one OpenAPI 3 file per service in [`contracts/src/main/resources/openapi/`](../../contracts/src/main/resources/openapi/). CI checks the services against them, and regenerates the web app's typed client from them. Every public call goes through the gateway (`http://localhost:8080` on Compose, `:18080` on kind).

| Service | Contract | Paths |
|---|---|---|
| identity | [identity-service.yaml](../../contracts/src/main/resources/openapi/identity-service.yaml) | `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout`; `GET /api/v1/users/me` |
| catalog | [catalog-service.yaml](../../contracts/src/main/resources/openapi/catalog-service.yaml) | `GET /api/v1/products` (search, category, sort, page), `GET /api/v1/products/{id}`; admin create, update and archive |
| inventory | [inventory-service.yaml](../../contracts/src/main/resources/openapi/inventory-service.yaml) | `GET /api/v1/inventory/{productId}`; admin stock view and adjustments |
| order | [order-service.yaml](../../contracts/src/main/resources/openapi/order-service.yaml) | cart, `POST /api/v1/orders` (idempotent), `GET /api/v1/orders/{id}`; admin orders, sagas, dead letters and replay |
| payment | [payment-service.yaml](../../contracts/src/main/resources/openapi/payment-service.yaml) | admin payment attempts and "reconcile now" |

## Rules every endpoint follows

- **Authentication:** a bearer access token from `/api/v1/auth/login`, valid for 15 minutes. The refresh token is an HttpOnly, SameSite=Strict cookie. `/admin/` paths need the ADMIN role.
- **Idempotency:** `POST /api/v1/orders` needs an `Idempotency-Key` of 16 to 64 letters, digits and dashes. The same key with the same body returns the original order (200); with a different body, 422.
- **Errors:** RFC 9457 `application/problem+json`, never with stack traces or SQL.
- **Rate limits:** per client address at the gateway (`429` with `X-RateLimit-*` headers): 50/s for the API and 5/s for sign-in.

## Browsing a contract

Any OpenAPI viewer works. For example, with Redoc in Docker:

```bash
docker run --rm -p 127.0.0.1:8088:80 -v "$PWD/contracts/src/main/resources/openapi/order-service.yaml:/usr/share/nginx/html/openapi.yaml:ro" -e SPEC_URL=openapi.yaml redocly/redoc
```

Then open http://localhost:8088.
