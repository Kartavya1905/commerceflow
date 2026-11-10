# ADR-0017: How performance is measured, and G1 for every service

- Status: Accepted
- Date: 2026-11-10
- Refines: NFR-05, ROADMAP Milestone 12

## Context
Milestone 12 measures NFR-05 (p95 < 300 ms for the catalogue and order reads at 500 users) and looks for the main bottleneck. Its review gate names four ways to fool yourself: a load generator competing with the system for CPU, a cold JVM in the results, averages, and tuning the test until it passes.

## Decisions
1. **One harness, kept with its evidence.**
   - `scripts/perf.sh` runs `load-tests/k6/perf-reads.js` against a data set built through the API.
   - It records the environment: hardware, versions, commit, JVM options, data set, and other containers.
   - It samples resource use every 10 s, and keeps the raw k6 summary in `docs/reports/data/perf/`.
   - A report may quote only what is in those files.
2. **Only the measured window counts.** Every request is tagged warm-up, measure or ramp-down, and the thresholds and percentiles read the measure phase only. The gateway, recreated for each run with raised rate limits, is warmed first.
3. **Percentiles and error rate, never averages:** p50, p95 and p99 per endpoint.
4. **k6 shares the machine, and says so.** There is no second machine for free. k6 is capped at 2 CPUs, and its own use is in each run's samples.
5. **Every run is reported, in order.** Weaker and failed runs are reported too, and repeated configurations show the noise. A single before-and-after pair is not a result.
6. **G1, chosen explicitly** (`-XX:+UseG1GC` in `deploy/docker/spring-boot-service.Dockerfile`). Below 1,792 MB of memory, the JVM picks the Serial collector by itself, which stops the whole service for each collection. In 768 MB containers it had done so in every service. At 500 users the gateway was paused 21 % of the time, and those pauses were the tail. With G1 it was paused 2.8 % of the time, and the longest pause in any service fell from 2.87 s to 0.16 s. p99 fell in every run after it. The median rose from about 13 ms to 27–45 ms, which is the price of G1 doing part of its work alongside the application. ParallelGC would favour throughput, and ZGC would need more headroom than a 768 MB container has. G1 is the JVM's own default for servers, and fits a tail-latency target.
7. **Trace sampling is configurable** (`TRACING_SAMPLING`, default 1.0 locally). Sampling 10 % halved Tempo's CPU but did not improve latency in the run measured, so the local default stays at every request, and real traffic would lower it (ADR-0014).

## Consequences
- Results depend on one laptop, and two identical runs differed by 20–30 % at p95. The report says so, and quotes ranges.
- Tempo is the component that does not hold up under sustained load: it sits at its memory limit and restarts. More memory, shorter retention and lower sampling come before any real use. [docs/reports/performance.md](../reports/performance.md) has the numbers.
