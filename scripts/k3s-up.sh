#!/usr/bin/env bash
# Deploys CommerceFlow to k3s, with images from a registry. The AWS demo instance runs it at first
# boot (infra/terraform/modules/ec2-k3s/bootstrap.sh.tftpl); scripts/k3s-test.sh runs it against a
# local k3s in Docker, which is how it is tested without AWS.
#
#   IMAGE_REGISTRY=ghcr.io/kartavya1905/commerceflow IMAGE_TAG=<commit, 12 characters> scripts/k3s-up.sh
#
# K3S_CONTEXT picks the kubectl context (default: "default", the one k3s writes). Secrets come from
# .env and secrets/jwt-private.pem. Where they do not exist yet, as on a new instance, they are
# generated here with random values, readable by this user only, and never leave the machine.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib/k8s.sh"

: "${IMAGE_REGISTRY:?set IMAGE_REGISTRY, such as ghcr.io/kartavya1905/commerceflow}"
: "${IMAGE_TAG:?set IMAGE_TAG, the first 12 characters of the commit}"
CONTEXT="${K3S_CONTEXT:-default}"

command -v kubectl > /dev/null || die "kubectl is needed (k3s installs it)"
kubectl --context "$CONTEXT" get --raw /readyz > /dev/null 2>&1 ||
  die "the cluster behind context $CONTEXT is not ready"

# Files created from here on are readable by this user only.
umask 077
ensure_env_file
ensure_jwt_key

info "Deploying tag $IMAGE_TAG from $IMAGE_REGISTRY"
k8s_deploy "$CONTEXT" overlays/aws-demo/infra overlays/aws-demo "$IMAGE_TAG" "$IMAGE_REGISTRY"
info "Ready (tag $IMAGE_TAG)"
