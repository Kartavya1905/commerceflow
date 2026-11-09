#!/usr/bin/env bash
# Tests the AWS demo's deploy path without AWS: k3s (the release the instance installs) in a Docker
# container, this checkout's images under the registry names the instance pulls, then
# scripts/k3s-up.sh with the aws-demo overlays, exactly as the instance's first boot runs it.
#
#   scripts/k3s-test.sh              build the images, start k3s, deploy
#   scripts/k3s-test.sh --no-build   reuse the last build (kind-up's or this script's)
#   scripts/k3s-test.sh --down       remove the k3s container
#
# Then: the web app on http://localhost:28000, the gateway on http://localhost:28080, and
#   E2E_BASE_URL=http://localhost:28000 scripts/e2e.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib/k8s.sh"

# The k3s the instance installs (infra/terraform/modules/ec2-k3s, k3s_version).
K3S_IMAGE=rancher/k3s:v1.36.4-k3s1
NAME=commerceflow-k3s
REGISTRY=ghcr.io/kartavya1905/commerceflow
PUBLIC_IMAGES=(postgres:18.6 apache/kafka:4.3.2 redis:8.10.3)
KUBECONFIG_FILE="$ROOT/deploy/k8s/.generated/k3s-kubeconfig"

build=true
for arg in "$@"; do
  case "$arg" in
    --no-build) build=false ;;
    --down)
      docker rm -f "$NAME" > /dev/null 2>&1 && info "Removed $NAME" || info "No $NAME container"
      exit 0
      ;;
    -h | --help)
      sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) die "unknown option: $arg (try --help)" ;;
  esac
done

require_docker
command -v kubectl > /dev/null || die "kubectl is needed (Docker Desktop includes it)"
ensure_env_file
ensure_jwt_key
k8s_images "$build"

if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
  for port in 16443 28000 28080; do
    ! port_in_use "$port" || die "port $port is in use"
  done
  info "Starting k3s ($K3S_IMAGE)"
  # The same flags as the instance: no bundled ingress or load balancer, NodePorts instead.
  docker run -d --name "$NAME" --privileged --tmpfs /run --tmpfs /var/run \
    -p 127.0.0.1:16443:6443 -p 127.0.0.1:28000:30000 -p 127.0.0.1:28080:30080 \
    "$K3S_IMAGE" server --disable=traefik --disable=servicelb --tls-san=127.0.0.1 > /dev/null
fi

for _ in $(seq 1 60); do
  docker exec "$NAME" test -s /etc/rancher/k3s/k3s.yaml 2> /dev/null && break
  sleep 2
done
docker exec "$NAME" cat /etc/rancher/k3s/k3s.yaml |
  sed 's#https://127.0.0.1:6443#https://127.0.0.1:16443#' > "$KUBECONFIG_FILE"
export KUBECONFIG="$KUBECONFIG_FILE"
for _ in $(seq 1 60); do
  kubectl --context default get --raw /readyz > /dev/null 2>&1 && break
  sleep 2
done

info "Importing the images under the registry's names, as if pulled from $REGISTRY"
platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
images=("${PUBLIC_IMAGES[@]}")
for image in "${PUBLIC_IMAGES[@]}"; do
  docker image inspect "$image" > /dev/null 2>&1 || docker pull --quiet "$image" > /dev/null
done
for image in "${APP_IMAGES[@]}" kafka-init; do
  docker tag "commerceflow/$image:$BUILD_TAG" "$REGISTRY/$image:$BUILD_TAG"
  images+=("$REGISTRY/$image:$BUILD_TAG")
done
docker save --platform "$platform" "${images[@]}" | docker exec -i "$NAME" ctr -n k8s.io images import - > /dev/null

IMAGE_REGISTRY="$REGISTRY" IMAGE_TAG="$BUILD_TAG" K3S_CONTEXT=default bash "$ROOT/scripts/k3s-up.sh"
cat << EOF
    Web app      http://localhost:28000
    API gateway  http://localhost:28080
    kubectl      KUBECONFIG=deploy/k8s/.generated/k3s-kubeconfig kubectl -n commerceflow get pods
    Smoke test   E2E_BASE_URL=http://localhost:28000 scripts/e2e.sh
    Remove       scripts/k3s-test.sh --down
EOF
