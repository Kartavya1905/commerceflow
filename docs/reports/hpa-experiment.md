# HPA experiment: catalog-service on kind

ROADMAP Milestone 10: "HPA scales catalog under k6 load and scales back", written up with real numbers. This is not a performance test. The numbers describe one laptop on one afternoon and are not performance claims.

## Setup

| | |
|---|---|
| Date | 2026-11-04 |
| Machine | Windows 11 laptop, Docker Desktop 29.9.2 with 10 CPUs and 16 GB. The Compose stack (19 containers, idle) was running alongside. |
| Cluster | kind 0.33.0, one node, Kubernetes 1.36.4, metrics-server 0.9.0 |
| Target | catalog-service: CPU request 250m and no CPU limit, memory 768 Mi. Products are cached in Redis. |
| Autoscaler | `deploy/k8s/base/catalog-hpa.yaml`: 1 to 4 pods at 60 % of the CPU request. Up by at most 2 pods every 30 s; down by 1 pod every 30 s, after a 120 s stabilization window. |
| Load | `load-tests/k6/catalog-hpa.js`, run by `scripts/kind-hpa.sh` as a Job in the cluster against `http://catalog-service:8082`, bypassing the gateway's per-client rate limit. Catalogue list and search requests at a fixed arrival rate: 30 s ramp, 4 min hold, 15 s ramp down. |
| Sampling | Every 15 s: the autoscaler's measured CPU (average % of request across ready pods), desired and current replicas, and ready pods. The samples start when the load Job is created. |

## Result (run 2: 200 requests per second)

| | |
|---|---|
| Requests | 52,499, all 200. None failed (threshold < 0.1 %) and none was dropped: k6 kept the rate. |
| Latency (in the cluster) | p50 1.37 ms, p95 4.08 ms, p99 12.23 ms, max 941 ms |
| Scale-up | 1 → 2 pods at 63 s, → 3 at 124 s, → 4 (the maximum) at 154 s (the autoscaler's events) |
| Scale-down | load ended at about 290 s. 4 → 3 at 334 s, 3 → 2 at 409 s, 2 → 1 at 439 s |
| Back at the minimum | about 150 s after the load ended |

| s | CPU % of request | desired | current | ready | load |
|---:|---:|---:|---:|---:|:---:|
| 0 | 1 | 1 | 1 | 1 | on |
| 47 | 28 | 1 | 1 | 1 | on |
| 62 | 50 | 1 | 1 | 1 | on |
| 78 | 104 | 2 | 1 | 2 | on |
| 93 | 66 | 2 | 2 | 2 | on |
| 125 | 84 | 3 | 2 | 2 | on |
| 141 | 95 | 3 | 3 | 3 | on |
| 157 | 147 | 4 | 3 | 3 | on |
| 174 | 86 | 4 | 4 | 4 | on |
| 205 | 93 | 4 | 4 | 4 | on |
| 236 | 39 | 4 | 4 | 4 | on |
| 282 | 32 | 4 | 4 | 4 | on |
| 313 | 4 | 4 | 4 | 4 | off |
| 345 | 1 | 3 | 4 | 3 | off |
| 422 | 1 | 2 | 3 | 2 | off |
| 453 | 1 | 1 | 2 | 1 | off |
| 468 | 1 | 1 | 1 | 1 | off |

Every pod that the autoscaler added was ready by the next sample (15–30 s). catalog-service starts in about 17 s.

## What it shows

1. **It scales up and back, without losing a request.** Three pods were added under load and removed afterwards. Not one of the 52,499 requests failed while pods started and stopped. A new pod takes traffic only once its readiness probe passes, and a pod that stops leaves the Service before it stops accepting, thanks to the 5 s `preStop` (ADR-0015).
2. **A cold JVM is expensive, and the autoscaler pays for it.** The load was the same throughout the hold, but CPU per pod fell. At 4 pods it went from 147 % to about 32 % of request, as the JIT compiled the hot paths and the caches filled. By the autoscaler's own formula, 4 pods at 32 % against a 60 % target need ⌈4 × 32 / 60⌉ = 3 pods. The fourth was bought by the warm-up, not the load. It stayed until the 120 s stabilization window passed the last high sample (205 s), which is the 334 s scale-down. The 5-minute default window would have held it longer. Ways to soften this, not taken here, are a higher target, a JIT warm-up before readiness, or a scale-up policy that waits longer before acting.
3. **Scaling down is deliberately slow.** The first pod went about 45 s after the load ended, and the rest over the next 105 s: the 120 s window, then at most 1 pod per 30 s. That trades a few minutes of idle pods for not flapping when load pauses briefly.

## Run 1: unbounded load (incomplete, kept as found)

The first attempt used 20 users sending as fast as they could, with no fixed rate. It went 1 → 3 → 4 pods within 70 s, then sat at the maximum with CPU at about 400 % of request: the load wanted more than 4 pods. After about 3 minutes, the k6 load generator was OOMKilled at its 256 Mi limit, because it was keeping every response body. The load stopped early, and k6 never printed its summary. The pods still scaled back to 1 afterwards. Run 2 replaced it with a fixed rate and discarded bodies, and `kind-hpa.sh` now fails when the load generator does not finish.

## Limits of this experiment

- **One node, shared.** The load generator, all of CommerceFlow and the idle Compose stack shared 10 CPUs. On a real cluster, new pods would land on other nodes.
- **Cached reads only.** This shows the autoscaler reacting, not catalog-service's capacity. Milestone 12 is the performance test.
- **One run of each.** The timings will vary by tens of seconds from run to run, and with the machine.

Reproduce: `scripts/kind-up.sh`, then `scripts/kind-hpa.sh` (or `RATE=300 HOLD=6m scripts/kind-hpa.sh`).
