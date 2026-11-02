# ADR-0008: Self-hosted payment sandbox service

- Status: Accepted
- Date: 2026-11-01

## Context
The project needs a payment provider with realistic behaviour: idempotency keys, declines, timeouts, unknown outcomes, refunds, and lookups for reconciliation. Provider test modes need an account, and some providers are invite-only in some countries. The project must cost nothing and run offline.

## Decision
Build `payment-sandbox`, a small Spring Boot service with its own database that behaves like a card-processing API: `POST /v1/charges` with `Idempotency-Key`, `GET /v1/charges?idempotency_key=`, `POST /v1/refunds`, outcome selected by token (`tok_success`, `tok_decline`, `tok_insufficient`, `tok_timeout`, `tok_unknown`), and a fault-injection API (added latency, drop the response after committing the charge). payment-service talks to it through a `PaymentProvider` interface.

## Consequences
- Scenarios such as "charged, then the acknowledgement was lost" are reproducible in automated tests.
- No card data exists anywhere in the system; tokens are opaque strings.
- A real provider adapter can be added behind the same interface later.

## Alternatives
- A hosted provider's test mode: realistic, but needs an account and network access, and cannot be made to fail on demand in the ways these tests need.
- An in-process fake: does not survive a payment-service restart, so the lost-acknowledgement scenario would not be real.
