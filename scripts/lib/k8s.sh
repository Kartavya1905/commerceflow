#!/usr/bin/env bash
# Deploying CommerceFlow to a Kubernetes cluster: shared by scripts/kind-up.sh (kind, images loaded
# into the node) and scripts/k3s-up.sh (the AWS demo instance, images from a registry). Source it
# after common.sh; do not run it.

# shellcheck disable=SC2034  # used by the scripts that source this file
APP_IMAGES=(identity-service catalog-service order-service inventory-service payment-sandbox
  payment-service api-gateway web)
K8S_NAMESPACE=commerceflow
# The image tag of the build in use, set by k8s_images.
BUILD_TAG=""

# k8s_images BUILD
#   BUILD true: builds the jars and images from this checkout and tags them with the commit (plus a
#   timestamp when the checkout has uncommitted changes), never "latest". false: reuses the last
#   build. Either way, sets BUILD_TAG.
k8s_images() {
  local build="$1" generated="$ROOT/deploy/k8s/.generated" image
  mkdir -p "$generated"
  if [[ "$build" == true ]]; then
    BUILD_TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
    [[ -z "$(git -C "$ROOT" status --porcelain)" ]] || BUILD_TAG="$BUILD_TAG-dirty-$(date +%Y%m%d%H%M%S)"
    build_jars
    info "Building images (tag $BUILD_TAG)"
    compose --profile apps build --quiet
    for image in "${APP_IMAGES[@]}" kafka-init; do
      docker tag "commerceflow/$image:local" "commerceflow/$image:$BUILD_TAG"
    done
    echo "$BUILD_TAG" > "$generated/tag"
  else
    [[ -s "$generated/tag" ]] || die "no earlier build to deploy; run again without --no-build"
    BUILD_TAG="$(cat "$generated/tag")"
    info "Using the last build (tag $BUILD_TAG)"
  fi
}

# k8s_deploy CONTEXT DATA_OVERLAY APP_OVERLAY TAG [REGISTRY]
#   CONTEXT       the kubectl context to deploy to
#   DATA_OVERLAY  the data stores' kustomization, relative to deploy/k8s (overlays/kind/infra)
#   APP_OVERLAY   the applications' kustomization, relative to deploy/k8s (overlays/kind)
#   TAG           the image tag every application image runs
#   REGISTRY      where the images are (ghcr.io/owner/commerceflow); without it, the local names
#                 (commerceflow/<image>) that kind-up loads into the node
#
# Creates the namespace, the Secrets from .env and secrets/jwt-private.pem, and the setup Jobs'
# ConfigMaps; applies the data stores and waits for them and the Jobs; then applies the
# applications and waits for every rollout.
k8s_deploy() {
  local context="$1" data_overlay="$2" app_overlay="$3" tag="$4" registry="${5:-}"
  local generated="$ROOT/deploy/k8s/.generated"
  mkdir -p "$generated"

  _k() {
    kubectl --context "$context" --namespace "$K8S_NAMESPACE" "$@"
  }

  # --- Namespace, secrets and configuration from local files -----------------------------------
  kubectl --context "$context" apply -k "$ROOT/deploy/k8s/namespace" > /dev/null

  # Values go through a private file under the git-ignored secrets/ and kubectl's stdout, never a
  # command line.
  local secret_keys=(POSTGRES_SUPERUSER POSTGRES_SUPERUSER_PASSWORD IDENTITY_DB_PASSWORD
    CATALOG_DB_PASSWORD ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD PAYMENT_DB_PASSWORD
    NOTIFICATION_DB_PASSWORD SANDBOX_DB_PASSWORD REDIS_PASSWORD SANDBOX_API_KEY
    BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD)
  local env_file key value
  env_file="$(mktemp "$ROOT/secrets/k8s-env.XXXXXX")"
  chmod 0600 "$env_file"
  for key in "${secret_keys[@]}"; do
    value="$(env_value "$key")"
    [[ -n "$value" ]] || { rm -f "$env_file"; die "$key is empty in .env"; }
    echo "$key=$value" >> "$env_file"
  done
  _k create secret generic commerceflow-secrets --from-env-file="$env_file" --dry-run=client \
    -o yaml | _k apply -f - > /dev/null
  rm -f "$env_file"
  local signing_key_file="$ROOT/secrets/jwt-private.pem"
  _k create secret generic jwt-private-key --dry-run=client -o yaml \
    --from-file "jwt-private.pem=$signing_key_file" | _k apply -f - > /dev/null
  _k create configmap postgres-init-script \
    --from-file=create-service-databases.sh="$ROOT/deploy/compose/postgres/create-service-databases.sh" \
    --dry-run=client -o yaml | _k apply -f - > /dev/null
  _k create configmap topic-catalog \
    --from-file=topics.json="$ROOT/contracts/src/main/resources/events/topics.json" \
    --dry-run=client -o yaml | _k apply -f - > /dev/null

  # --- The image tags of this build, on top of the committed overlays --------------------------
  _write_kustomization() {
    local dir="$generated/$1" resources="$2" image
    shift 2
    mkdir -p "$dir"
    {
      echo "# Written by scripts/lib/k8s.sh for tag $tag; not committed."
      echo "apiVersion: kustomize.config.k8s.io/v1beta1"
      echo "kind: Kustomization"
      echo "resources:"
      echo "  - $resources"
      echo "images:"
      for image in "$@"; do
        echo "  - name: commerceflow/$image"
        [[ -z "$registry" ]] || echo "    newName: $registry/$image"
        echo "    newTag: \"$tag\""
      done
    } > "$dir/kustomization.yaml"
  }
  _write_kustomization infra "../../$data_overlay" kafka-init
  _write_kustomization apps "../../$app_overlay" "${APP_IMAGES[@]}"

  # --- Data stores first, then the applications ------------------------------------------------
  info "Starting Postgres, Kafka and Redis"
  # A Job's pod template cannot change; the two setup Jobs are idempotent and simply run again.
  _k delete job postgres-init kafka-init --ignore-not-found > /dev/null
  _k apply -k "$generated/infra" > /dev/null
  local statefulset deployment
  for statefulset in postgres kafka redis; do
    _k rollout status "statefulset/$statefulset" --timeout=300s > /dev/null
  done
  _k wait --for=condition=complete job/postgres-init job/kafka-init --timeout=300s > /dev/null ||
    die "a setup job did not finish; see: kubectl --context $context -n $K8S_NAMESPACE logs job/postgres-init (or job/kafka-init)"

  info "Deploying the applications"
  _k apply -k "$generated/apps" > /dev/null
  for deployment in "${APP_IMAGES[@]}"; do
    # What `kubectl rollout history` shows for this revision.
    _k annotate "deployment/$deployment" kubernetes.io/change-cause="deploy $tag" --overwrite > /dev/null
  done
  for deployment in "${APP_IMAGES[@]}"; do
    _k rollout status "deployment/$deployment" --timeout=300s > /dev/null ||
      die "$deployment did not become ready; see: kubectl --context $context -n $K8S_NAMESPACE describe deployment/$deployment"
  done
}
