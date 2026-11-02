# ADR-0005: JSON events with JSON Schema contracts in the repository

- Status: Accepted
- Date: 2026-11-01

## Context
Producers and consumers are deployed independently, so message formats need a checked contract and a versioning rule. The project must run at no cost on a laptop.

## Decision
Messages are JSON with a common envelope. Each event type has a JSON Schema (draft 2020-12) in `contracts/`, plus valid and invalid examples. Contract tests run in JUnit with networknt json-schema-validator 3.x (Jackson 3). Producer schemas set `additionalProperties: false`; consumers are tolerant readers. Compatibility rules are in ARCHITECTURE.md section F.6.

## Consequences
- No schema-registry container to run; contracts are reviewed in pull requests like code.
- Compatibility is enforced by CI tests over stored examples, not by a registry at publish time.
- JSON is larger than Avro or Protobuf on the wire; irrelevant at this scale.

## Alternatives
- Avro with Confluent Schema Registry (Confluent Community License) or Apicurio Registry (Apache 2.0): runtime enforcement and smaller messages, at the cost of another service. Apicurio is the documented upgrade path.
- Protobuf: strong typing, but harder to read when inspecting topics by hand.
