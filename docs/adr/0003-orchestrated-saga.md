# ADR-0003: Orchestrated saga in order-service

- Status: Accepted
- Date: 2026-11-01

## Context
Checkout touches inventory and payment, each with its own database. A distributed transaction (2PC/XA) is not available across Kafka, separate databases and an external payment provider, and it would couple service availability.

## Decision
order-service runs a persisted saga orchestrator (`saga_instances`). It reacts to events, sends commands through its outbox, and drives compensations (`ReleaseInventory`, `CancelPayment`). Transitions are an explicit table of (state, event) → (new state, commands).

## Consequences
- The whole workflow is visible in one place and testable as a pure transition table.
- order-service is the logical coordinator but not a single point of availability: it scales horizontally because each saga is protected by a row lock.
- Other services stay simple: they handle commands and publish results.

## Alternatives
- Choreography (each service reacts to the previous event): fewer moving parts, but the workflow is implicit and compensation logic is spread across services, which makes the failure paths hard to reason about and test.
- A workflow engine (Temporal, Camunda): strong, but it hides the mechanics this project is meant to demonstrate and adds heavy infrastructure.
