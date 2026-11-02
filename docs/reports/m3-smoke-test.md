# Milestone 3 smoke test: catalog browsing through the gateway

**What this is:** a functional check that public browsing works under light concurrent load with no errors. **What this is not:** a performance result. The NFR-05 target (p95 < 300 ms at 500 users) is measured in Milestone 12 with a proper workload, warm-up, and a load generator on separate hardware.

## Setup

| | |
|---|---|
| Date | 2026-11-01 |
| Script | [`load-tests/k6/smoke-catalog.js`](../../load-tests/k6/smoke-catalog.js), k6 2.2.0 in Docker on the compose network |
| Workload | 10 virtual users, 1 minute. Each iteration: list or search products (one of 5 terms or none), then fetch the first result, then sleep 0.5 s |
| Path | k6 → api-gateway → catalog-service → Redis cache / PostgreSQL |
| Data | 8 seeded products plus a few left by `check-infra` runs (a tiny catalogue, so the cache hit rate is high) |
| Hardware | Windows 11 laptop, 10 logical CPUs, 31 GB RAM, Docker Desktop with 16 GB. k6, gateway, services and databases all on the same machine |
| Containers | each service limited to 768 MB; JVMs started a few minutes earlier, no dedicated warm-up |

## Result

| Metric | Value |
|---|---|
| Requests | 2,340 (≈ 39 per second) |
| Failed requests | 0 (threshold: 0) |
| Checks passed | 3,510 of 3,510 |
| Latency avg / median | 7.7 ms / 6.4 ms |
| Latency p90 / p95 / max | 12.4 ms / 15.8 ms / 53.6 ms |

## Limits of this result

- The load generator shares CPU with the system under test.
- 39 requests per second is far below the gateway's per-client limit and far below the M12 target load.
- With so few products almost every read is a cache hit; the M12 data set must be large enough that it is not.
- One run, no repetition, no confidence interval.

## How to reproduce

```bash
scripts/dev-up.sh --apps
docker run --rm -i --network commerceflow_default -e BASE_URL=http://api-gateway:8080 grafana/k6:2.2.0 run - < load-tests/k6/smoke-catalog.js
```
