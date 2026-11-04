# ADR-0015: Kubernetes on kind

- Status: Accepted
- Date: 2026-11-04
- Refines: ADR-0006 (one Postgres server), ROADMAP Milestone 10

## Context
Milestone 10 runs the same system on local Kubernetes. Its review gate names four defects to rule out: a readiness probe that checks Kafka and Postgres, so one broker blip takes every pod out; no resource limits, so the JVM sizes itself from the node's memory; Secrets committed as base64; and `latest` image tags.

## Decisions

### Layout
1. **Kustomize, with the applications in one base** (`deploy/k8s/base`). An overlay adds what differs per environment: where the data stores are, how traffic gets in, and which image tags run. The data stores' addresses are one ConfigMap (`endpoints`). A cloud overlay (Milestone 11) replaces it with managed endpoints and keeps everything else.
2. **kind's data stores are an overlay of their own** (`overlays/kind/infra`): Postgres, a single-broker KRaft Kafka and Redis as StatefulSets, plus the two setup Jobs. Those Jobs use the same script and topic catalogue as Compose; `kind-up` puts them into ConfigMaps. `kind-up` applies this overlay first and waits for it, so the applications start against a ready database and existing topics instead of crash-looping until then.
3. **No ingress controller.** The web app and the gateway are NodePorts that the kind node maps to `127.0.0.1:18000` and `:18080`. These are not Compose's 8000 and 8080, so both stacks can run at once. Both use `externalTrafficPolicy: Local`, so the gateway sees the caller's address rather than the node's.

### The gate
4. **Readiness checks only what the pod needs to serve; liveness only the process.** Each service's readiness group is `readinessState,db`: its own database and nothing else. The gateway's is `readinessState`. Kafka is never in either. The outbox holds events while a broker is away, so serving on is right. A startup probe covers the JVM's start, so liveness can be strict without killing slow starters. This is in each `application.yml`, so Compose and Kubernetes behave the same. It was tested on both. Stopping Postgres made readiness answer 503 while liveness stayed 200. Stopping Kafka changed nothing. On kind, a frozen pod left the Service after two failed readiness probes and was restarted by liveness.
5. **Memory request equals the limit; CPU has a request and no limit.** The JVM sizes its heap from the container's limit (`MaxRAMPercentage=75` in the image), so a 768 Mi pod gets a 576 Mi heap. With request equal to limit, a pod is never killed for using memory it was scheduled with. A CPU limit would throttle the JVM's start and its garbage collector. CPU is compressible, so the request alone sets each pod's share under contention, and it is the figure the autoscaler measures against.
6. **Secrets are created, never committed.** `kind-up` creates `commerceflow-secrets` from the local `.env` and `jwt-private-key` from `secrets/jwt-private.pem`. Values pass through a private file under the git-ignored `secrets/`, never a command line. Each pod reads only the keys it needs, through `secretKeyRef`.
7. **Images are tagged with the commit.** A tag is the short commit hash, plus a timestamp when the checkout has uncommitted changes. It is never `latest`, so every rollout names one build and `kubectl rollout undo` has a real previous version to return to. `kind-up` writes the tags into a git-ignored kustomization on top of the overlays. Images are loaded into the node, so it never pulls.
8. **The gate is a script.** `scripts/check-k8s.sh` renders every kustomization and validates it against the Kubernetes 1.36 API schema with kubeconform in strict mode. It then fails on any of these:
   - a missing or `latest` tag;
   - a Secret object;
   - a container without a memory limit equal to its request, or without a CPU request;
   - a long-running container without liveness and readiness probes;
   - a health group that includes Kafka;
   - a pod not locked down;
   - a Deployment without a PodDisruptionBudget.

   Eight planted defects were each caught. It runs in CI with ShellCheck.

### Hardening
9. **The namespace enforces the restricted Pod Security level**, and every pod meets it, the data stores included. Each runs as its image's own non-root user, with no privilege escalation, all capabilities dropped and the default seccomp profile. Every root filesystem is read-only, with emptyDirs where an image must write: `/tmp`, and for Kafka its config and log directories. Kafka's image writes the broker's configuration when it starts. No pod gets a service account token, and none gets service-link variables. Those would set `REDIS_PORT=tcp://…`, which the services read, and `KAFKA_*` variables, which the broker reads.
10. **Every Deployment has a PodDisruptionBudget of `maxUnavailable: 1`.** Two replicas then keep one serving through a drain, and one replica does not block a drain. Rolling updates use `maxSurge: 1, maxUnavailable: 0`, so a release that never becomes ready leaves the old pods serving. A `preStop` sleep of 5 s lets Services stop routing to a pod before it stops accepting. Spring then drains for up to 30 s, inside a 40 s grace period.
11. **Replicas follow the work.** The gateway, order-service and web run two replicas each. The outbox publisher takes an advisory lock and the sweepers use `SKIP LOCKED`, so two order-service pods share the work safely. catalog-service is scaled by CPU, from 1 to 4 pods. The others run one replica.
12. **nginx learns its DNS server from the pod.** The web image renders its upstream at start: the resolver from `/etc/resolv.conf` and the gateway's address from `GATEWAY_ADDRESS`. Its resolver ignores search domains, so on Kubernetes that address is the gateway's full name. The gateway trusts the web pods' own addresses through a headless Service, `web-proxies`, which it re-resolves every 30 s. A request through nginx is rate-limited by the caller's address, as on Compose; Redis' bucket keys show it.

## Consequences
- The HPA experiment's numbers and their limits are in [docs/reports/hpa-experiment.md](../reports/hpa-experiment.md).
- `kubectl rollout undo` leaves the cluster differing from Git; the next `kind-up` puts back what Git says. Undo is the emergency move, and the real fix is a revert.
- Docker Desktop's containerd image store keeps a multi-platform image for one platform only, and `kind load docker-image` then fails. Public images are saved for the node's platform and loaded as an archive.
- Observability stays on Compose: kind runs the applications and metrics-server, not Prometheus, Grafana, Tempo or Loki.
