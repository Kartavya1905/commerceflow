#!/usr/bin/env bash
# Checks the Terraform without an AWS account (ROADMAP M11): nothing here needs credentials,
# creates anything, or costs anything.
#
#   scripts/check-terraform.sh
#
# 1. terraform fmt: every file formatted.
# 2. Each stack (bootstrap, demo-ec2, reference-eks): init without a backend, using the committed
#    provider lock file, and validate.
# 3. terraform test: each module's gate tests, and reference-eks' cost guard, as plans against a
#    mocked AWS provider.
# 4. tflint with the AWS ruleset.
# 5. checkov: every finding fixed or skipped with its reason next to the resource.
# 6. The gate, directly: no port 22 anywhere; every stack tags what it creates with the project
#    (the deploy role's rights and the watcher depend on it); no state file in Git.
#
# Runs Terraform, tflint and checkov from pinned images, the same locally and in CI.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

TF_IMAGE=hashicorp/terraform:1.16.6
TFLINT_IMAGE=ghcr.io/terraform-linters/tflint:v0.64.0
CHECKOV_IMAGE=bridgecrew/checkov:3.3.29
TF_DIR="$ROOT/infra/terraform"
STACKS=(bootstrap demo-ec2 reference-eks)
TESTED=(modules/network modules/ec2-k3s modules/github-oidc envs/reference-eks)

require_docker

# tf DIR ARGS...: terraform in DIR (relative to infra/terraform), with a shared provider cache.
tf() {
  local dir="$1"
  shift
  docker run --rm -v "$ROOT:/repo" -v commerceflow-tf-plugins:/plugins \
    -e TF_PLUGIN_CACHE_DIR=/plugins -e TF_IN_AUTOMATION=1 \
    -w "/repo/infra/terraform/$dir" "$TF_IMAGE" "$@"
}

info "terraform fmt"
tf . fmt -recursive -check -diff || die "run: docker run --rm -v \"\$PWD:/repo\" -w /repo/infra/terraform $TF_IMAGE fmt -recursive"

for stack in "${STACKS[@]}"; do
  info "terraform validate: envs/$stack"
  tf "envs/$stack" init -backend=false -input=false -lockfile=readonly -no-color > /dev/null ||
    die "init failed for envs/$stack (a provider not in its .terraform.lock.hcl?)"
  tf "envs/$stack" validate -no-color
done

for dir in "${TESTED[@]}"; do
  info "terraform test: $dir"
  tf "$dir" init -backend=false -input=false -no-color > /dev/null
  tf "$dir" test -no-color
done

info "tflint"
# A token, where there is one (CI), keeps the plugin download clear of GitHub's anonymous limit.
docker run --rm -v "$ROOT:/repo" -v commerceflow-tflint-plugins:/root/.tflint.d \
  -e GITHUB_TOKEN="${GITHUB_TOKEN:-}" -w /repo/infra/terraform --entrypoint sh "$TFLINT_IMAGE" -c \
  'tflint --init --config /repo/infra/terraform/.tflint.hcl > /dev/null &&
   tflint --recursive --config /repo/infra/terraform/.tflint.hcl --format compact'

info "checkov"
docker run --rm -v "$ROOT:/repo:ro" "$CHECKOV_IMAGE" -d /repo/infra/terraform \
  --framework terraform --compact --quiet --skip-path '\.terraform'

info "Gate"
failures=0
# Our own files only: downloaded modules (.terraform/) carry their authors' tests and examples.
if grep -rn --include='*.tf' --exclude-dir=.terraform -E '(from_port|to_port)\s*=\s*22\b' "$TF_DIR"; then
  echo "FAIL port 22 is opened above; administration is through Session Manager"
  failures=$((failures + 1))
else
  echo "ok   no port 22"
fi
for stack in "${STACKS[@]}"; do
  if ! grep -q -E '^\s*Project\s*=\s*"commerceflow"' "$TF_DIR/envs/$stack/main.tf"; then
    echo "FAIL envs/$stack does not tag what it creates with Project = commerceflow (default_tags)"
    failures=$((failures + 1))
  fi
done
((failures > 0)) || echo "ok   every stack tags what it creates with the project"
tracked_state=$(git -C "$ROOT" ls-files -- '*.tfstate' '*.tfstate.*' '*.tfplan' '**/.terraform/**')
if [[ -n "$tracked_state" ]]; then
  echo "FAIL state or plan files are in Git: $tracked_state"
  failures=$((failures + 1))
else
  echo "ok   no state or plan file in Git"
fi

((failures == 0)) || die "$failures gate check(s) failed"
info "All Terraform checks passed"
