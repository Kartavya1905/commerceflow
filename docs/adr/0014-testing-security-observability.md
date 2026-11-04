# ADR-0014: Testing, security and observability

- Status: Accepted
- Date: 2026-11-03
- Refines: ADR-0010 (outbox: trace context), ROADMAP Milestone 9

## Context
Milestone 9 closes coverage gaps, adds security scanning and a security test suite, and makes failures visible on dashboards and alerts. Its review gate names four defects to rule out: high-cardinality metric labels, personal data in logs, dashboards that only show averages, and alerts without a runbook.

## Decisions

### Tests and architecture
1. **Coverage is enforced where the business rules live.** JaCoCo merges unit and integration test coverage per module. Each service's build fails if any of its domain or saga packages drops below 80 % of lines (NFR-11): catalog `product`, identity `user` and `token`, inventory `reservation` and `stock`, order `order`, `saga` and `cart`, payment `payment` and `provider`. All of them are at 90-100 %. Configuration, controllers and the shared libraries are reported but not held to a number. Their code is mostly exercised by the services' tests, which the per-module reports do not count.
2. **Architecture rules are tests.** ArchUnit, shared as `ArchitectureRules` in test-support and run by every service:
   - no service depends on another;
   - no controller touches `JdbcClient`, `JdbcTemplate` or a repository;
   - no service sends to Kafka except through the outbox;
   - no field injection, standard streams or java.util.logging.

   Each library has its own check that it depends on no service. The first run found three controllers querying the database directly; their queries now live in `AccountService.profile`, `SagaQueries` and `PaymentQueries`. The payment sandbox is exempt from the controller rule and says why: it is a stand-in for an outside provider.

### Security
3. **A black-box security suite runs against the real stack** (`SecurityIT`, `scripts/security-test.sh`). It goes through the doors a client has. Forged tokens are re-signed with the stack's own key, so expiry, audience and issuer are each proven to be checked, not just the signature. A control re-sign with unchanged claims must still be accepted. docs/security/SECURITY.md maps every threat to its control and test.
4. **Redaction is a net, not the plan.** Code never logs personal data or secrets. `RedactingLogCustomizer` masks emails, JWTs, Bearer values, secret fields, sandbox payment tokens and card-like numbers in every JSON log line, including exception messages and stack traces. Card numbers are matched only when they pass the Luhn check, so timestamps survive. `LogRedactionIT` proves it through the service's real logging, and `SecurityIT` scans the running services' logs.
5. **Scanning splits by what each tool sees best.** gitleaks scans the whole git history. Trivy scans the repository for npm dependencies, Dockerfile and Compose misconfiguration, and secrets. Trivy also scans every built image, which covers the Java dependencies from the exact jars shipped; resolving them from `pom.xml` would download the whole dependency tree, and Maven Central rate-limits that. CodeQL runs `security-extended` on Java and TypeScript. All of them run in pinned images through `scripts/scan-security.sh`, the same locally and in CI, with a weekly scheduled run for newly published vulnerabilities. A HIGH or CRITICAL finding with a fix fails the build unless `.trivyignore` says why it is accepted; it is empty.

### Observability
6. **One trace per checkout.** Every service, the gateway included, uses Spring Boot's OpenTelemetry starter. The outbox stores the writing request's `traceparent` with each event, and listener observation continues it on the consumer side. Spans leave only when `TRACING_EXPORT` is on (`dev-up --obs`), so without the collector nothing retries an absent endpoint. Logs carry `traceId`, `spanId` and `correlationId`. The starter's OTLP log and metric push are off: logs stay on stdout, and Prometheus scrapes metrics.
7. **Latency is a histogram with fixed buckets**: 25, 50, 100, 200, 300 and 500 ms, and 1, 2 and 5 s. That is 9 buckets per route instead of the default ~70, and 300 ms (NFR-05) is an edge, so the read-latency alert is exact. Dashboards show p95 and p99 and rates, never averages.
8. **Metric labels come from bounded sets.** Routes are URI templates. Counters for unknown commands no longer carry the command type; it goes to the log instead. `messaging.consumed` labels only event types in the contract catalogue, and anything else is `other`. `SecurityIT` checks every service's metrics for ids and emails in labels, and for metrics with more than 100 label combinations.
9. **Every alert is tested and has a runbook.** `alerts.test.yml` gives each of the 13 alerts synthetic series and checks, with promtool, when it fires, with which labels and annotations, and that it clears. `scripts/check-alerts.sh` fails if an alert's runbook link is missing or points at a heading that does not exist. New alerts: `ServerErrors` (500s, not 503s, above 5 %) and `SlowReads` (p95 above 300 ms on the read endpoints).

## Consequences
- Trivy found HIGH and CRITICAL vulnerabilities with fixes in two places: the Kafka client's compression libraries (`lz4-java`, `snappy-java`), now pinned to fixed versions in the parent pom until `kafka-clients` moves; and the nginx base image's Alpine packages, upgraded at build time. The pins have to be removed by hand once they are no longer needed.
- Every request is sampled. That suits a local system; under real traffic, the sampling probability has to come down, or tail sampling has to move to the collector.
- The security suite depends on the stack's own signing key in `secrets/`, so it runs only where the stack runs (locally and in the CI `infra` job).
