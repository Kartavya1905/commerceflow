#!/usr/bin/env bash
# Runs CommerceFlow on a local Kubernetes cluster (kind) and waits until every pod is ready.
#
#   scripts/kind-up.sh              build the images from this checkout, create the cluster if
#                                   needed, and deploy
#   scripts/kind-up.sh --no-build   deploy the images the last build made (for a quick re-apply)
#
# Then: the web app on http://localhost:18000, the gateway on http://localhost:18080. The Compose
# stack can keep running (it uses other ports), but both together need a lot of memory.
#
# Images are tagged with the commit (plus a timestamp when the checkout has uncommitted changes),
# never "latest", so every rollout names exactly one build and `kubectl rollout undo` has a real
# previous version to go back to. Secrets are created in the cluster from .env and
# secrets/jwt-private.pem, the same local values Compose uses; none are written to Git.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib/k8s.sh"

CLUSTER=commerceflow
NAMESPACE="$K8S_NAMESPACE"
CONTEXT="kind-$CLUSTER"
# Pinned by digest, as the kind release notes recommend; the version kubeconform validates against
# (scripts/check-k8s.sh).
NODE_IMAGE=kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed
# Loaded into the node from this machine, so the node never pulls them from a registry.
INFRA_IMAGES=(postgres:18.6 apache/kafka:4.3.2 redis:8.10.3)
GENERATED="$ROOT/deploy/k8s/.generated"

build=true
for arg in "$@"; do
  case "$arg" in
    --no-build) build=false ;;
    -h | --help)
      sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) die "unknown option: $arg (try --help)" ;;
  esac
done

require_docker
command -v kind > /dev/null || die "kind is needed: winget install Kubernetes.kind (or see https://kind.sigs.k8s.io)"
command -v kubectl > /dev/null || die "kubectl is needed (Docker Desktop includes it)"
ensure_env_file
ensure_jwt_key
mkdir -p "$GENERATED"

# --- 1. Images (scripts/lib/k8s.sh) ------------------------------------------------------------
k8s_images "$build"

# --- 2. Cluster ---------------------------------------------------------------------------------
if ! kind get clusters 2> /dev/null | grep -qx "$CLUSTER"; then
  info "Creating the kind cluster (first run downloads the node image, about 1 GB)"
  kind create cluster --name "$CLUSTER" --image "$NODE_IMAGE" \
    --config "$ROOT/deploy/k8s/kind/cluster.yaml" --wait 120s
fi

info "Loading images into the cluster"
kind_load_public "$CLUSTER" "${INFRA_IMAGES[@]}"
images=()
for image in "${APP_IMAGES[@]}" kafka-init; do
  images+=("commerceflow/$image:$BUILD_TAG")
done
# kind reports each image it loads on stderr; only a failure is worth showing.
if ! kind load docker-image --name "$CLUSTER" "${images[@]}" > /dev/null 2> "$GENERATED/kind-load.log"; then
  cat "$GENERATED/kind-load.log" >&2
  die "loading the images into the cluster failed"
fi

info "Installing metrics-server (for the HorizontalPodAutoscaler)"
kubectl --context "$CONTEXT" apply -k "$ROOT/deploy/k8s/addons/metrics-server" > /dev/null

# --- 3. Secrets, data stores, then the applications (scripts/lib/k8s.sh) -----------------------
k8s_deploy "$CONTEXT" overlays/kind/infra overlays/kind "$BUILD_TAG"

info "Ready (tag $BUILD_TAG)"
cat << EOF
    Web app      http://localhost:18000   (admin: BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD in .env)
    API gateway  http://localhost:18080
    kubectl      kubectl --context $CONTEXT -n $NAMESPACE get pods
    Smoke test   E2E_BASE_URL=http://localhost:18000 scripts/e2e.sh
    Remove       scripts/kind-down.sh
EOF
