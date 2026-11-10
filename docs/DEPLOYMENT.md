# Deployment, upgrades and rollback

Three ways to run CommerceFlow, all from the same images and, for Kubernetes, the same manifests.

| | How | Images | Secrets | Details |
|---|---|---|---|---|
| **Compose** | `scripts/dev-up.sh --obs --apps` | built locally, tagged `local` | `.env` and `secrets/`, generated on first run | [SETUP.md](SETUP.md), [runbooks/infra.md](runbooks/infra.md) |
| **kind** | `scripts/kind-up.sh` | built locally, tagged with the commit, loaded into the node | created in the cluster from `.env`, never in Git | [ADR-0015](adr/0015-kubernetes-on-kind.md), [runbooks/kubernetes.md](runbooks/kubernetes.md) |
| **AWS demo (optional)** | the `aws-demo` workflow, after a reviewer's approval | GHCR, tagged with the commit | generated on the instance at first boot, never leaving it | [ADR-0016](adr/0016-terraform-and-aws-demo.md), [runbooks/aws-demo.md](runbooks/aws-demo.md) |

## Upgrading

**Compose.** `scripts/dev-up.sh --obs --apps` again. It rebuilds the jars and the images, and recreates only the containers that changed. Database migrations (Flyway) run when each service starts. They only add columns and tables, so a running older version keeps working while a newer one starts.

**kind.** Commit, then `scripts/kind-up.sh`. The images are tagged with the new commit, and every Deployment rolls out with `maxSurge: 1, maxUnavailable: 0`: a new pod starts, and an old one stops only once the new one is ready. The script waits for every rollout.

**AWS demo.** It is not upgraded in place. Run `destroy`, then `apply` with the new commit. It lives a few hours and holds no data worth keeping.

## Rolling back

**kind.** A release whose pods never become ready leaves the old ones serving. To go back:

```bash
kubectl --context kind-commerceflow -n commerceflow rollout undo deployment/<name>
kubectl --context kind-commerceflow -n commerceflow rollout status deployment/<name>
```

`scripts/kind-rollback.sh` shows it end to end: a broken release, the old pod serving throughout, and `rollout undo`, with 0 failed requests in 121 ([Milestone 10 checklist](CHECKLIST.md#milestone-10--kubernetes-on-kind)). Undo is the emergency move. Revert the change in Git too, or the next `kind-up` brings it back.

**Compose.** Check out the previous commit, and run `scripts/dev-up.sh --obs --apps`.

**Schema.** Migrations are not rolled back; a fix goes forward as a new migration. Because each one only adds, the previous version of a service runs against the newer schema, so rolling back the code does not need the schema rolled back.

## Before any deploy

CI runs on every push to `main`. It covers:
- the build with all tests, the frontend, ShellCheck and the alert tests;
- the Compose stack with check-infra, the browser smoke test and the security suite;
- the kind deploy with pod failures and a rollback;
- the Terraform checks and the k3s deploy;
- the security scans: gitleaks, Trivy and CodeQL.

A deploy starts from a commit where all of it passed.
