# Runbook: CommerceFlow on Kubernetes (kind)

How the system runs on a local kind cluster, and what to do when it does not. Decisions and their reasons: [ADR-0015](../adr/0015-kubernetes-on-kind.md). The Compose stack has its own runbook: [infra.md](infra.md).

## Running it

Needs Docker, [kind](https://kind.sigs.k8s.io) (`winget install Kubernetes.kind` on Windows) and kubectl (Docker Desktop includes it).

```bash
scripts/kind-up.sh                                   # build from this checkout and deploy (first run ~10 min)
scripts/kind-up.sh --no-build                        # re-apply the last build
E2E_BASE_URL=http://localhost:18000 scripts/e2e.sh   # the browser smoke test against kind
scripts/kind-chaos.sh                                # checkouts while pods hang and are deleted
scripts/kind-rollback.sh                             # a bad release, then kubectl rollout undo
scripts/kind-hpa.sh                                  # the HPA experiment (~10 min)
scripts/check-k8s.sh                                 # validate the manifests, no cluster needed
scripts/kind-down.sh                                 # delete the cluster and its data
```

The web app is at http://localhost:18000 and the gateway at http://localhost:18080, beside Compose's 8000 and 8080. The admin login is the one in `.env`. Every command below uses `kubectl --context kind-commerceflow -n commerceflow`, shortened here to `k`.

## A pod is not ready

```bash
k get pods
k describe pod <pod>          # Events: probe failures, image problems, OOMKilled
k logs <pod> --previous       # the last run, if it restarted
```

- **Startup probe failed** while the pod is young is the JVM starting; the startup probe allows 3 minutes. If it keeps failing, the log says why.
- **Readiness probe failed** on a service means its own database is unreachable. Kafka is never part of readiness (ADR-0015), so check `k get pod postgres-0` and the service's `*_DB_PASSWORD` in `commerceflow-secrets`.
- **OOMKilled**: the heap is 75 % of the memory limit, and the rest is metaspace, threads and buffers. Raise the request and the limit together.
- **CreateContainerConfigError**: the pod reads a key that `commerceflow-secrets` does not have. Add the key to `secret_keys` in `scripts/kind-up.sh`, then run `scripts/kind-up.sh --no-build` to recreate the Secret.

## A setup job failed

`kind-up` stops with "a setup job did not finish".

```bash
k logs job/postgres-init
k logs job/kafka-init
```

Both are idempotent, and `kind-up` runs them again each time. Most often `kafka-init` refused a partition-count change, which is never applied automatically ([infra.md](infra.md#kafka-init-refuses-a-partition-change)).

## A release is bad

A release whose pods never become ready leaves the old ones serving (`maxUnavailable: 0`):

```bash
k rollout status deployment/<name>      # stuck, waiting for the new pods
k rollout history deployment/<name>     # each revision with its tag
k rollout undo deployment/<name>        # back to the previous revision
```

Undo is the emergency move: the cluster then differs from Git, and the next `kind-up` applies Git again. Revert the change in Git as well. `scripts/kind-rollback.sh` demonstrates this with no failed request.

## The autoscaler does nothing

```bash
k get hpa catalog-service               # TARGETS shows cpu: <current>/60%
kubectl --context kind-commerceflow top pods -n commerceflow
```

`<unknown>` means metrics-server has no numbers yet. Its first scrape takes about a minute after a pod starts. If it stays unknown, check `kubectl --context kind-commerceflow -n kube-system logs deploy/metrics-server`. Utilization is measured against the CPU request (250m), so 60 % means 150m per pod on average.

## Images fail to load

`kind load docker-image` fails with "content digest … not found" on public images under Docker Desktop's containerd image store: it keeps a multi-platform image for one platform only. `kind-up` handles this by saving those images for the node's platform and loading the archive. If an image you added fails the same way, put it in `INFRA_IMAGES`.

## Ports 18000 or 18080 are taken

The kind node maps its NodePorts to these host ports when the cluster is created. Free them, or change `hostPort` in `deploy/k8s/kind/cluster.yaml`, then run `scripts/kind-down.sh` and `scripts/kind-up.sh`. A cluster's port mappings cannot change while it exists.
