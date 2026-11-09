# Runbook: the optional AWS demo

CommerceFlow on one EC2 instance running k3s, created and destroyed by Terraform (`infra/terraform/envs/demo-ec2`, [ADR-0016](../adr/0016-terraform-and-aws-demo.md)). **Optional, and never needed:** CI validates and tests all of the Terraform without an AWS account, and `scripts/k3s-test.sh` runs the instance's deploy path on a local k3s. Nothing below happens unless you choose to do it, and it costs money while the demo runs.

## What it costs

us-east-1 on-demand prices, from AWS's public price list (published 2026-11-03). Check them again before you apply; prices change.

| Item | Price | Demo |
|---|---|---|
| EC2 t3.xlarge (4 vCPU, 16 GiB), Linux | $0.1664 per hour | 1 instance |
| EBS gp3 storage | $0.08 per GB-month | 30 GB, about $0.003 per hour |
| Public IPv4 address, in use | $0.005 per hour | 1 |
| **While it runs** | | **about $0.18 per hour** (a four-hour demo: about $0.70) |
| **Once destroyed** | | **$0** |

No NAT gateway, load balancer, Elastic IP or detailed monitoring is created. CPU credits are "standard", so a busy instance is slowed down rather than billed beyond the hourly price. Moving data out to the internet is free for the first 100 GB a month. The state bucket costs a fraction of a cent a month (a few kilobytes, 30 days of versions).

Three things limit a forgotten demo:
1. **The instance terminates itself** `max_hours` (default 4, at most 12) after it boots, whether or not anyone runs destroy. Its disk goes with it, and its public address is released. What is left (VPC, subnet, security group, IAM role) costs nothing.
2. **A scheduled check** (`aws-demo` workflow, every two hours) fails while any CommerceFlow instance exists, and GitHub emails you about the failure.
3. **The destroy step below**, and the console check after it.

## Once: the AWS side

Needs an AWS account. On AWS's free plan, new accounts get credits and are not billed while they stay on it. Do not enable AWS Organizations, Control Tower or IAM Identity Center: they turn the account into a paid one (ARCHITECTURE.md K.3).

1. Open **AWS CloudShell** in us-east-1. It is free, already signed in, and needs no access key.
2. Install Terraform 1.16.6 there (HashiCorp's Linux zip, checked against its SHA256SUMS), and clone this repository.
3. `cd infra/terraform/envs/bootstrap && terraform init && terraform apply`. This creates the state bucket, GitHub's OIDC provider, the deploy role and the watcher role, and prints the three values for the next step. Its state stays in CloudShell: role ARNs and a bucket name, no secrets.

## Once: the GitHub side

1. **Settings → Environments → New environment `aws-demo`**: add yourself as a required reviewer, and allow only the `main` branch. Add the environment secret `AWS_ADMIN_CIDR` with your address as a /32, such as `203.0.113.7/32` (check it at https://checkip.amazonaws.com). Use a secret, not a workflow input: the run's log is public, and GitHub masks secrets in it.
2. **Settings → Variables → Actions**: add `AWS_REGION` = `us-east-1`, plus `AWS_STATE_BUCKET`, `AWS_DEPLOY_ROLE_ARN` and `AWS_WATCHER_ROLE_ARN` from the bootstrap's output. Last, add `AWS_DEMO_ENABLED` = `true`; without it, the workflow does nothing.

## Run a demo

1. **Actions → aws-demo → Run workflow**, with action `apply` and the hours you want.
2. The `publish` job pushes the commit's images to GHCR. The first time, open each new package (Your profile → Packages → `commerceflow/<service>`) and make it **public**, so the instance can pull it without a token. Then re-run the workflow.
3. Approve the `terraform` job when GitHub asks. It prints `web_url`, `api_url` and `session_command`.
4. Wait about 10 minutes for the first boot: packages, k3s, the data stores, then nine services. To watch it, run the printed `aws ssm start-session` command, then `sudo tail -f /var/log/commerceflow-bootstrap.log`.
5. Open `web_url` from your own address. The admin's password is on the instance: `sudo grep BOOTSTRAP_ADMIN /opt/commerceflow/.env`.
6. Smoke test it from your machine: `E2E_BASE_URL=<web_url> scripts/e2e.sh`.

## Destroy it

1. **Actions → aws-demo → Run workflow** with action `destroy`, and approve it.
2. **Confirm in the console** that nothing billable is left. In the EC2 console (us-east-1), Instances shows none running and Volumes shows none from the demo. Elastic IPs shows none. In VPC → Your VPCs, `commerceflow-demo` is gone. Billing → Bills shows only the hours the demo ran.
3. If the instance already terminated itself, run destroy anyway: it removes the VPC, the security group and the IAM role, which cost nothing but should not linger.

## When something is wrong

- **The page does not load:** your address changed. Update `AWS_ADMIN_CIDR` and run apply again; only the security group rule changes.
- **The instance boots but the services do not start:** check `/var/log/commerceflow-bootstrap.log`. Most often the images were not public (`ImagePullBackOff` in `kubectl -n commerceflow get pods`, run with `sudo` on the instance).
- **apply is refused with AccessDenied:** the deploy role allows only what this stack needs, in its own region, on resources tagged `Project = commerceflow`. Anything new in the stack needs its permission added in `modules/github-oidc` (and the bootstrap applied again).
- **The scheduled check fails:** something is still running. Destroy it as above.
