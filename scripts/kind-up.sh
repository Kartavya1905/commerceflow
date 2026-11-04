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

CLUSTER=commerceflow
NAMESPACE=commerceflow
CONTEXT="kind-$CLUSTER"
# Pinned by digest, as the kind release notes recommend; the version kubeconform validates against
# (scripts/check-k8s.sh).
NODE_IMAGE=kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed
APP_IMAGES=(identity-service catalog-service order-service inventory-service payment-sandbox
  payment-service api-gateway web)
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

k() {
  kubectl --context "$CONTEXT" --namespace "$NAMESPACE" "$@"
}

require_docker
command -v kind > /dev/null || die "kind is needed: winget install Kubernetes.kind (or see https://kind.sigs.k8s.io)"
command -v kubectl > /dev/null || die "kubectl is needed (Docker Desktop includes it)"
ensure_env_file
ensure_jwt_key
mkdir -p "$GENERATED"

# --- 1. Images ----------------------------------------------------------------------------------
if $build; then
  tag="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
  [[ -z "$(git -C "$ROOT" status --porcelain)" ]] || tag="$tag-dirty-$(date +%Y%m%d%H%M%S)"
  build_jars
  info "Building images (tag $tag)"
  compose --profile apps build --quiet
  for image in "${APP_IMAGES[@]}" kafka-init; do
    docker tag "commerceflow/$image:local" "commerceflow/$image:$tag"
  done
  echo "$tag" > "$GENERATED/tag"
else
  [[ -s "$GENERATED/tag" ]] || die "no earlier build to deploy; run scripts/kind-up.sh without --no-build"
  tag="$(cat "$GENERATED/tag")"
  info "Deploying the last build (tag $tag)"
fi

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
  images+=("commerceflow/$image:$tag")
done
# kind reports each image it loads on stderr; only a failure is worth showing.
if ! kind load docker-image --name "$CLUSTER" "${images[@]}" > /dev/null 2> "$GENERATED/kind-load.log"; then
  cat "$GENERATED/kind-load.log" >&2
  die "loading the images into the cluster failed"
fi

info "Installing metrics-server (for the HorizontalPodAutoscaler)"
kubectl --context "$CONTEXT" apply -k "$ROOT/deploy/k8s/addons/metrics-server" > /dev/null

# --- 3. Namespace, secrets and configuration from local files ------------------------------------
kubectl --context "$CONTEXT" apply -k "$ROOT/deploy/k8s/namespace" > /dev/null

# Values go through a private file under the git-ignored secrets/ and kubectl's stdout, never a
# command line.
secret_keys=(POSTGRES_SUPERUSER POSTGRES_SUPERUSER_PASSWORD IDENTITY_DB_PASSWORD CATALOG_DB_PASSWORD
  ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD PAYMENT_DB_PASSWORD NOTIFICATION_DB_PASSWORD
  SANDBOX_DB_PASSWORD REDIS_PASSWORD SANDBOX_API_KEY BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD)
env_file="$(mktemp "$ROOT/secrets/k8s-env.XXXXXX")"
trap 'rm -f "$env_file"' EXIT
chmod 0600 "$env_file"
for key in "${secret_keys[@]}"; do
  value="$(env_value "$key")"
  [[ -n "$value" ]] || die "$key is empty in .env"
  echo "$key=$value" >> "$env_file"
done
k create secret generic commerceflow-secrets --from-env-file="$env_file" --dry-run=client -o yaml |
  k apply -f - > /dev/null
rm -f "$env_file"
signing_key_file="$ROOT/secrets/jwt-private.pem"
k create secret generic jwt-private-key --dry-run=client -o yaml \
  --from-file "jwt-private.pem=$signing_key_file" | k apply -f - > /dev/null
k create configmap postgres-init-script \
  --from-file=create-service-databases.sh="$ROOT/deploy/compose/postgres/create-service-databases.sh" \
  --dry-run=client -o yaml | k apply -f - > /dev/null
k create configmap topic-catalog \
  --from-file=topics.json="$ROOT/contracts/src/main/resources/events/topics.json" \
  --dry-run=client -o yaml | k apply -f - > /dev/null

# The image tags of this build, on top of the committed overlays.
write_kustomization() {
  local dir="$GENERATED/$1" resources="$2" image
  shift 2
  mkdir -p "$dir"
  {
    echo "# Written by scripts/kind-up.sh for tag $tag; not committed."
    echo "apiVersion: kustomize.config.k8s.io/v1beta1"
    echo "kind: Kustomization"
    echo "resources:"
    echo "  - $resources"
    echo "images:"
    for image in "$@"; do
      echo "  - name: commerceflow/$image"
      echo "    newTag: \"$tag\""
    done
  } > "$dir/kustomization.yaml"
}
write_kustomization infra ../../overlays/kind/infra kafka-init
write_kustomization apps ../../overlays/kind "${APP_IMAGES[@]}"

# --- 4. Data stores first, then the applications ------------------------------------------------
info "Starting Postgres, Kafka and Redis"
# A Job's pod template cannot change; the two setup Jobs are idempotent and simply run again.
k delete job postgres-init kafka-init --ignore-not-found > /dev/null
k apply -k "$GENERATED/infra" > /dev/null
for statefulset in postgres kafka redis; do
  k rollout status "statefulset/$statefulset" --timeout=300s > /dev/null
done
k wait --for=condition=complete job/postgres-init job/kafka-init --timeout=300s > /dev/null ||
  die "a setup job did not finish; see: kubectl --context $CONTEXT -n $NAMESPACE logs job/postgres-init (or job/kafka-init)"

info "Deploying the applications"
k apply -k "$GENERATED/apps" > /dev/null
for deployment in "${APP_IMAGES[@]}"; do
  # What `kubectl rollout history` shows for this revision.
  k annotate "deployment/$deployment" kubernetes.io/change-cause="kind-up $tag" --overwrite > /dev/null
done
for deployment in "${APP_IMAGES[@]}"; do
  k rollout status "deployment/$deployment" --timeout=300s > /dev/null ||
    die "$deployment did not become ready; see: kubectl --context $CONTEXT -n $NAMESPACE describe deployment/$deployment"
done

info "Ready (tag $tag)"
cat << EOF
    Web app      http://localhost:18000   (admin: BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD in .env)
    API gateway  http://localhost:18080
    kubectl      kubectl --context $CONTEXT -n $NAMESPACE get pods
    Smoke test   E2E_BASE_URL=http://localhost:18000 scripts/e2e.sh
    Remove       scripts/kind-down.sh
EOF
