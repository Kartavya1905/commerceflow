# Performance: NFR-05 and the limits of one laptop

ROADMAP Milestone 12. Every number below comes from a run whose raw k6 summary, environment record and resource samples are in [`data/perf/`](data/perf/). Runs are listed as they happened, the first failing or weaker results included. These numbers describe this machine on these days, not a capacity claim for any other.

**NFR-05:** p95 under 300 ms for `GET /products`, `GET /products/{id}` and `GET /orders/{id}` at 500 virtual users, on the documented workload and hardware. **Met in all five load runs, by a margin of 16 to 112 ms, and not under a fast ramp-up or beyond 500 users (stress, below).**

At a glance:
- **500 users, 5 minutes measured, five runs:** p95 188–284 ms, p99 396–876 ms, 0 errors in about 1.86 million measured requests, about 1,250 a second.
- **The bottleneck found and fixed:** the JVM had picked the Serial garbage collector by itself in 768 MB containers. The gateway spent 21 % of its time stopped, which made the tail. With G1 that fell to 2.8 %, the longest pause in any service fell from 2.87 s to 0.16 s, and p99 fell in every run after it. The median rose, which is part of the trade.
- **Stress:** under a fast ramp-up, already overloaded at 500 users, by the sign-in surge. Throughput peaks at 750 users (557 reads/s); errors pass 1 % at 1,500 and reach 3 % at 2,000.
- **Soak, 30 minutes at 250 users:** p95 96–109 ms, p99 200–229 ms, 0 errors in 1.25 million requests, no heap growth.
- **Weak spots:** Tempo, at its memory limit and restarting; run-to-run variation of 20–30 % at p95; and the load generator sharing the machine.

## Setup

| | |
|---|---|
| Machine | Windows 11 laptop, AMD Ryzen 9 9955HX (Windows reports 10 logical processors), 31 GB of RAM. Docker Desktop 29.9.2 with 10 CPUs and 16 GB |
| Also running | 26 idle containers of another project (about 0.15 % CPU each) |
| System | The Compose stack (`scripts/dev-up.sh --obs --apps`): 7 Spring Boot services at 768 MB each (the sandbox 512 MB), Postgres, Kafka, Redis, and the observability stack (Prometheus, Grafana, Tempo, Loki, the OpenTelemetry collector), every request traced |
| Load generator | k6 2.3.0 in Docker on the **same machine**, capped at 2 CPUs. It used 0.45–0.59 CPU on average (0.55–0.59 in the load runs), recorded in each run's `docker-stats.csv` |
| Data | 2,065 products (two seeds of 1,000, plus 65 from earlier tests), 100 customers, 500 confirmed orders, created through the API (`load-tests/k6/perf-seed.js`) |
| Path | k6 → api-gateway → catalog-service (Redis cache, Postgres) or order-service (Postgres), over Compose's network |
| Gateway limits | The per-client rate limits were raised for each run, because every virtual user comes from one address (`scripts/perf.sh` restores them afterwards) |

**Workload** (`load-tests/k6/perf-reads.js`, profile `load`): each virtual user is one of the 100 customers and repeats four steps. First a search (one of 8 terms) or a category page (one of 8). Then one product from the result. Then one of its own orders. Then 0.5–1.5 s of thinking. The run warms up from 0 to 500 users over 2 minutes, holds 500 users for 5 minutes, then ramps down. **Only the 5 minutes at 500 users are measured**; the warm-up and ramp-down are tagged and left out. Percentiles are per endpoint, from 120,000–128,000 requests each per run.

## Load at 500 users

| Run | GC | Tracing | list p50 / p95 / p99 | product p50 / p95 / p99 | order p50 / p95 / p99 | Errors |
|---|---|---|---|---|---|---|
| [1](data/perf/20261109-222757-load/summary.txt) | Serial (the JVM's own choice) | every request | 15.6 / **283.9** / 701.1 ms | 12.2 / **262.9** / 657.4 ms | 13.6 / **263.8** / 875.6 ms | 0 of 379,476 |
| [2](data/perf/20261109-224047-load/summary.txt) | G1 | every request | 27.9 / **209.1** / 442.4 ms | 25.6 / **198.0** / 402.7 ms | 26.8 / **188.0** / 396.0 ms | 0 of 383,980 |
| [3](data/perf/20261109-225252-load/summary.txt) | G1 | 10 % of requests | 36.4 / **266.5** / 468.9 ms | 33.5 / **248.9** / 426.3 ms | 34.6 / **240.3** / 422.3 ms | 0 of 370,386 |
| [4](data/perf/20261109-230527-load/summary.txt) | G1 | every request | 41.5 / **269.9** / 518.8 ms | 37.8 / **248.3** / 482.4 ms | 39.5 / **240.1** / 455.8 ms | 0 of 365,607 |
| [5](data/perf/20261110-083924-load/summary.txt), the next day | G1 | every request | 45.3 / **279.6** / 489.9 ms | 41.5 / **258.1** / 444.8 ms | 42.4 / **258.0** / 451.5 ms | 0 of 363,742 |

In each measured window the three endpoints together served 1,212–1,280 requests per second (k6 kept the load; no request failed). Run 5 is a control, repeated after the stress run below to check that the machine had not changed overnight. The laptop was on AC power, on Windows' Balanced plan.

## The bottleneck: garbage collection in the gateway

Run 1's median was 12–16 ms, but its p95 was about 20 times that: a few requests waited much longer than the rest. The services' own metrics (Prometheus, measured on the server side) showed where:

| Run 1, server side | p95 | p99 | Time paused for GC | Longest GC pause |
|---|---|---|---|---|
| api-gateway | 216 ms | 697 ms | **21 %** | 0.49 s |
| catalog-service | 24 ms | 45 ms | 3 % | 2.32 s |
| order-service | 24 ms | 47 ms | 1.6 % | 2.22 s |

Catalog and order answered in about 24 ms at p95; the gateway was where requests waited. Its collector was `Copy` / `MarkSweepCompact`, that is the **Serial collector**. Nobody had chosen it. Below 1,792 MB of memory, the JVM decides it is not on a server-class machine and picks Serial, which stops the whole application for every collection. Every container here has 768 MB. The gateway, the busiest JVM, spent a fifth of its time stopped, and every request that arrived during a pause waited it out.

**The fix:** `-XX:+UseG1GC` in the one image recipe all services share (`deploy/docker/spring-boot-service.Dockerfile`). In run 2, on the server side:

| Server side | Serial (run 1) | G1 (run 2) |
|---|---|---|
| Gateway time paused for GC | 20.9 % | **2.8 %** |
| Gateway's longest pause | 0.49 s | **0.11 s** |
| Longest pause in any service | 2.87 s | **0.16 s** |
| Gateway p95 | 216 ms | 177 ms |

## What the load runs do and do not show

- **G1 removed the long pauses, and p99 fell in every G1 run.** It went from 657–876 ms to 396–519 ms. This is the one improvement that is larger than the variation between runs.
- **p95 improved in run 2, by 26–29 %, and much less in run 4,** which repeated run 2's configuration. Runs 2 and 4 differ by 20–30 % at p95 with nothing changed, so the variation between runs on this machine is about that large. A single before-and-after pair would have overstated the gain.
- **The median got worse with G1,** going from 12–16 ms to 26–42 ms. G1 does part of its work alongside the application, so it costs a little on every request in exchange for far fewer long stops. For the tail-latency target here, that is the right trade, but it is a trade.
- **Latency rose a little with every run** (runs 2 to 5, at p50 27, 35, 40 and 43 ms), with no change to the code. Candidates are the laptop's temperature over an hour of load, and Tempo's background work on the traces kept from earlier runs: Tempo used more CPU in each run, from 1.5 to 3.6 CPUs on average over the load runs, and 4.2 in the soak, more than any service, as it kept more traces. Neither was isolated.
- **Tracing 10 % of requests instead of all of them did not help** (run 3). The hypothesis that span export was the second bottleneck is refuted for latency. Tempo's CPU fell by half, but latency did not.
- **What still limits the gateway is not known.** After the GC fix, its p50 is about 23 ms while its p95 is still 177 ms. The most likely cause is CPU contention on a machine where k6, the system and the observability stack share 10 CPUs. Measuring it would need the load generator, or the observability stack, on another machine.

## Stress: where it breaks

Profile `stress` ([summary](data/perf/20261110-082158-stress/summary.txt)): steps of 500, 750, 1,000, 1,500 and 2,000 users. Each step is ramped to in 30 s and held for 2 minutes; the first is reached in 1 minute instead of the load profile's 2. Each held step is measured on its own. The reads per endpoint are below; logins are left out of these columns.

| Users | list p50 / p95 / p99 | product p50 / p95 / p99 | order p50 / p95 / p99 | Reads per second | Errors (all requests) |
|---|---|---|---|---|---|
| 500 | 498 / 1,803 / 3,542 ms | 466 / 1,707 / 2,819 ms | 431 / 1,388 / 2,153 ms | 319 | 1.08 % |
| 750 | 864 / 2,454 / 3,476 ms | 797 / 2,279 / 3,400 ms | 820 / 2,150 / 3,212 ms | **557** (the peak) | 0.03 % |
| 1,000 | 1,872 / 4,341 / 5,669 ms | 1,773 / 4,304 / 5,549 ms | 1,761 / 4,275 / 5,519 ms | 392 | 0.26 % |
| 1,500 | 2,879 / 7,087 / 9,044 ms | 2,793 / 6,276 / 8,183 ms | 2,965 / 7,783 / 15,770 ms | 327 | 1.28 % |
| 2,000 | 3,077 / 11,214 / 15,094 ms | 2,906 / 9,528 / 14,560 ms | 2,965 / 8,819 / 14,362 ms | 317 | 3.00 % |

- **Under a fast ramp-up, it is already overloaded at 500 users.** The load profile held 500 users at a p95 of 240–280 ms and about 1,250 reads per second. The stress profile's 500-user step managed 319 reads per second at a p95 of 1.4–1.8 s. The difference is the ramp. Every new virtual user signs in first, and a sign-in is an Argon2 password hash, CPU-heavy by design. Reaching 500 users in one minute instead of two doubles that wave: identity-service averaged 1.15 CPUs in this run against 0.09–0.26 in the load runs, on a machine that was already busy. Run 5, the load profile again straight afterwards, held 500 users within the target, so the machine had not changed.
- **Throughput peaks at 750 users and then falls.** Beyond that, more users add only waiting: at 2,000 users, p99 reached k6's 60-second request timeout, and 3 % of requests failed. The system degrades by getting slower, then by timing out. No CommerceFlow service or data store restarted during it, in this run or any other (all restart counts were 0 afterwards).
- **What gives way is CPU on one machine.** In this run Tempo averaged 2.0 CPUs, the gateway 1.6, identity-service 1.1 and k6 0.6. A sign-in surge is the obvious thing to protect: the gateway's sign-in rate limit, raised for these runs, exists for it (5 per second per address by default).

## The observability stack under load

Tempo, the trace store, is the one component that did not hold up. Every request is traced, and with about 1,250 requests a second, each producing spans in the gateway and a service, Tempo:
- used more CPU than any service, rising from 1.5 CPUs on average in the first run to 4.2 in the soak as it kept more traces;
- sat at its 512 MB memory limit in every run (the `docker-stats.csv` samples);
- restarted 11 times over the two days, once during the soak. After the restart at 04:44 UTC it reported "failed to replay block. removing": traces received before the restart were lost.

Docker did not record why each restart happened. Running at the memory limit and restarting is consistent with Tempo being starved of memory, but that was not confirmed. The services were not affected: tracing is asynchronous, and their restart counts stayed at 0. Two follow-ups would matter before any real use: a larger memory limit and a shorter retention for Tempo, and sampling well under 100 % of requests (ADR-0014 already says so for real traffic). Kafka's metrics exporter also restarted, 9 times, all within a minute of the stack starting, while Kafka itself was coming up; that is unrelated to load.

## Soak: 30 minutes at 250 users

Profile `soak` ([summary](data/perf/20261110-084836-soak/summary.txt)): warm up to 250 users over 2 minutes, hold them for 30 minutes (measured), ramp down.

| Endpoint | p50 | p95 | p99 |
|---|---|---|---|
| list | 11.9 ms | 109.2 ms | 229.1 ms |
| product | 10.7 ms | 96.9 ms | 204.4 ms |
| order | 12.9 ms | 96.0 ms | 200.1 ms |

There were 0 errors in 1,247,688 measured requests. Over the 30 minutes, measured in Prometheus at four points in the hold:

| Into the run | Gateway p95 (server side) | Gateway heap | Old generation (5-minute max) | Time paused for GC |
|---|---|---|---|---|
| 7 min | 130 ms | 68 MiB | 58 MiB | 1.8 % |
| 17 min | 48 ms | 84 MiB | 58 MiB | 1.3 % |
| 27 min | 156 ms | 78 MiB | 57 MiB | 2.0 % |
| 34 min | 91 ms | 53 MiB | 61 MiB | 1.2 % |

- **No sign of a heap leak.** The old generation, where anything kept by mistake accumulates, stayed at 57–61 MiB, and GC time stayed under 2 %. Latency moved between samples without a trend. catalog-service and order-service held their memory flat (652 to 654 MiB and 585 to 584 MiB).
- **The gateway's container memory rose from 350 to 487 MiB** while its heap did not. That growth is outside the heap: native memory such as network buffers and compiled code. It stayed well inside its 768 MB limit, but 30 minutes is not long enough to show whether it levels off. A longer soak would.
- **No service or data store restarted.** Tempo restarted once more during this run (11 times in all; see above).

## Runs that measured nothing

- **The first seed** stopped at the first order: its Idempotency-Key was 15 characters, one short of the contract's minimum. The 1,000 products it had already created stayed, so the catalogue has 2,065 products rather than 1,065.
- **The first smoke run** ([data](data/perf/20261109-160422-smoke/)) recorded no request in its measured window. The gateway had just been recreated for the raised limits, and its first requests took up to 94 s. The runner now warms the recreated gateway with 300 reads before any run; [the second smoke run](data/perf/20261109-180832-smoke/summary.txt) measured p95 23–35 ms at 5 users.

## Reproducing

```bash
scripts/dev-up.sh --obs --apps
scripts/perf.sh smoke      # seeds the data on first use
scripts/perf.sh load       # also: stress, soak
```

Each run writes `docs/reports/data/perf/<time>-<profile>/`: `summary.txt` (what this report quotes), `summary.json` (k6's full summary), `environment.json` and `docker-stats.csv`.
