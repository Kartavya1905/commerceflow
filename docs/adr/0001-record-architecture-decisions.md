# ADR-0001: Record architecture decisions

- Status: Accepted
- Date: 2026-11-01

## Context
The project makes many trade-offs (consistency model, locking, messaging) that reviewers will ask about. Decisions explained only in chat or commit messages get lost.

## Decision
Every significant decision gets a short ADR in `docs/adr/`, numbered, with Context, Decision, Consequences, and Alternatives. An accepted ADR is not rewritten; a new ADR supersedes it.

## Consequences
Reviewers and interviewers can trace any design choice to its reasoning. Writing an ADR is part of each milestone's exit gate.
