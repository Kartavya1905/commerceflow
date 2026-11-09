# ADR-0016: Terraform and the optional AWS demo

- Status: Accepted
- Date: 2026-11-04
- Refines: ARCHITECTURE.md K, ROADMAP Milestone 11

## Context
Milestone 11 writes the Terraform for a demo environment on AWS, the single EC2 instance with k3s that ARCHITECTURE.md K.2 recommends, plus a reference EKS stack, all validated in CI. Applying it is optional, and this project has no budget, so it is never applied here. The review gate names five defects to rule out: a security group open to `0.0.0.0/0`, IAM `*` actions, Terraform state with secrets committed, forgetting to destroy, and resources created outside Terraform.

## Decisions

### Layout
1. **Three modules and three stacks** (`infra/terraform`):
   - `modules/network`: one VPC, one public subnet, an internet gateway. No NAT gateway, no load balancer.
   - `modules/ec2-k3s`: the instance, its security group and its role.
   - `modules/github-oidc`: GitHub's OIDC provider and two roles.
   - `envs/bootstrap`: applied once by the account owner. The state bucket and the GitHub roles.
   - `envs/demo-ec2`: the demo, applied and destroyed by the `aws-demo` workflow.
   - `envs/reference-eks`: EKS, RDS, MSK and ElastiCache. Validate-only.
2. **The demo runs the Milestone 10 manifests unchanged.** The `aws-demo` overlay is the base with NodePorts, and its data stores are kind's. `scripts/k3s-up.sh` deploys it through the same code as `kind-up` (`scripts/lib/k8s.sh`), with images from GHCR, which is free for public packages.

### The gate
3. **One address, two ports.** The security group admits `admin_cidr` only, on the web app's and the gateway's NodePorts. The variable refuses anything wider than a /24, and the runbook uses a /32. There is no SSH: no key pair, no port 22. A shell is through Session Manager, which is the instance role's only permission. IMDSv2 is required, with a hop limit of 1, so pods cannot reach the instance's credentials.
4. **No `*` actions; rights scoped by tag, name and region.** GitHub signs in with OIDC, so there are no access keys. The deploy role trusts only the repository's `aws-demo` Environment, which needs a reviewer's approval for every run. Its policy:
   - names every action;
   - lets EC2 create only resources carrying `Project = commerceflow`, and change or delete only resources that already carry it, in one region;
   - limits IAM to the `commerceflow-demo-*` role and instance profile;
   - lets it attach exactly one policy, Session Manager's, so it cannot grant itself more.

   The watcher role only describes instances and volumes. The deploy policy is written from the calls Terraform makes. It is checked statically and by tests, but it has not been exercised against AWS, because nothing is applied.
5. **Secrets never reach Terraform.** The instance generates its passwords and JWT key at first boot, from `.env.example` with random values, readable by root only, and they never leave it. The user data holds only the commit, versions and registry. State is in the bootstrap's S3 bucket: encrypted, versioned, private, TLS only, and locked with S3's own lock file. The bootstrap's own state stays in CloudShell and holds ARNs, not secrets. `*.tfstate` is git-ignored, and the checks fail on any tracked state.
6. **A forgotten demo stops itself.** The user data's first step schedules a shutdown `max_hours` after boot (default 4, at most 12), and shutting down terminates the instance, with its disk and its public address. A scheduled workflow, every two hours, fails while any project instance exists, and GitHub emails the owner. The runbook ends with a console check.
7. **Nothing outside Terraform.** Every stack's provider tags all it creates with `Project = commerceflow`. The deploy role cannot touch untagged resources, and the watcher finds them by that tag. The VPC's default security group is left as AWS creates it: nothing is placed in it, and managing it would mean letting the deploy role change untagged groups.

### Cost
8. **About $0.18 an hour while it runs, nothing once destroyed** (runbooks/aws-demo.md, AWS's prices of 2026-11-03). A t3.xlarge is enough for the whole stack, with standard CPU credits, so bursts are throttled, not billed. There is no NAT gateway, load balancer, Elastic IP, detailed monitoring, flow logs or KMS key; each checkov finding that would add one is skipped with that reason, on the resource itself.
9. **The reference stack cannot be applied by accident.** A plan fails unless `acknowledge_hourly_cost` is true. A test proves both halves.

### Checks (`scripts/check-terraform.sh`, in CI)
10. **Everything runs without an AWS account:**
    - `terraform fmt`;
    - `validate` of each stack against its committed provider lock file;
    - `terraform test`: 16 runs, as plans against mocked providers;
    - tflint with the AWS ruleset;
    - checkov: 217 passed, 0 failed, 12 skipped, each with its reason;
    - the gate's own checks: no port 22, every stack tagged, no state in Git.

    Twelve planted defects were each caught. Registry modules are pinned to the commit of their release, and tools to their images.

## Consequences
- The demo's deploy path is tested on every push without AWS: `scripts/k3s-test.sh` runs the instance's `k3s-up.sh` against k3s v1.36.4 in Docker, with the images under their GHCR names.
- GHCR packages are private when first pushed; the runbook makes each public once, by hand.
- An apply that hits a permission the policy lacks fails with AccessDenied. The fix is in `modules/github-oidc`, then the bootstrap is applied again.
