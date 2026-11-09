#!/usr/bin/env bash
# Validates the Kubernetes manifests without a cluster (ROADMAP M10 gate).
#
#   scripts/check-k8s.sh
#
# 1. Renders every kustomization: base, the kind and AWS demo overlays with their data stores, and
#    metrics-server.
# 2. kubeconform: each object against the Kubernetes API schema of the version kind and k3s run,
#    strictly (an unknown or misspelt field fails).
# 3. Policy, the review gate's defects as checks:
#    - every image has a tag, and none is "latest";
#    - no Secret object (the deploy creates them from .env, scripts/lib/k8s.sh; never committed);
#    - every container has a memory limit equal to its request, and a CPU request;
#    - every long-running container has liveness and readiness probes, and no Spring Boot
#      service's readiness group includes Kafka;
#    - every pod runs as non-root with no privilege escalation and all capabilities dropped;
#    - every Deployment has a PodDisruptionBudget;
#    - the Spring Boot image sizes its heap from the container limit (MaxRAMPercentage).
#
# Runs kubectl's built-in kustomize on the host, and kubeconform and yq from pinned images.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

KUBECONFORM_IMAGE=ghcr.io/yannh/kubeconform:v0.8.0
YQ_IMAGE=mikefarah/yq:4.53.6
# The Kubernetes version of the kind node image (deploy/k8s/kind/cluster.yaml, scripts/kind-up.sh).
K8S_VERSION=1.36.4

require_docker
command -v kubectl > /dev/null || die "kubectl is needed (Docker Desktop includes it)"

out="$ROOT/deploy/k8s/.rendered"
rm -rf "$out" && mkdir -p "$out"
for target in base overlays/kind overlays/kind/infra overlays/aws-demo overlays/aws-demo/infra \
  addons/metrics-server; do
  kubectl kustomize "$ROOT/deploy/k8s/$target" > "$out/${target//\//-}.yaml" ||
    die "kustomize could not render deploy/k8s/$target"
done
info "Rendered $(ls "$out" | wc -l) kustomizations into deploy/k8s/.rendered"

info "kubeconform (Kubernetes $K8S_VERSION, strict)"
docker run --rm -v "$out:/manifests:ro" "$KUBECONFORM_IMAGE" \
  -strict -summary -kubernetes-version "$K8S_VERSION" /manifests

info "Policy checks"
yq() {
  docker run --rm -v "$out:/manifests:ro" "$YQ_IMAGE" eval -N "$@"
}

# One row per container: owner, list, name, image, memory request, memory limit, CPU request,
# liveness, readiness, runAsNonRoot, seccomp, allowPrivilegeEscalation, drops ALL,
# enableServiceLinks, automountServiceAccountToken.
ROW='select(.kind == "Deployment" or .kind == "StatefulSet" or .kind == "Job")
  | (.spec.template.spec.containers[], .spec.template.spec.initContainers[])
  | [parent(5).kind + "/" + parent(5).metadata.name, (parent | key), .name, .image,
     (.resources.requests.memory | tostring), (.resources.limits.memory | tostring),
     (.resources.requests.cpu | tostring),
     (has("livenessProbe") | tostring), (has("readinessProbe") | tostring),
     (parent(2).securityContext.runAsNonRoot | tostring),
     (parent(2).securityContext.seccompProfile.type | tostring),
     (.securityContext.allowPrivilegeEscalation | tostring),
     ((.securityContext.capabilities.drop // []) | contains(["ALL"]) | tostring),
     (parent(2).enableServiceLinks | tostring),
     (parent(2).automountServiceAccountToken | tostring)] | @tsv'
# Every deployable kustomization, one at a time: yq given several files reads only the first.
DEPLOYED=(overlays-kind.yaml overlays-kind-infra.yaml overlays-aws-demo.yaml overlays-aws-demo-infra.yaml)
rows=""
for file in "${DEPLOYED[@]}"; do
  rows+="$(yq "$ROW" "/manifests/$file")"$'\n'
done
rows="$(grep -v '^$' <<< "$rows")"
info "$(wc -l <<< "$rows" | tr -d ' ') containers"

failures=0
# check DESCRIPTION AWK-CONDITION: the condition picks the violating rows.
check() {
  local found
  found=$(awk -F'\t' "$2 {print \"       \" \$1 \"/\" \$3 \"  \" \$4}" <<< "$rows")
  if [[ -n "$found" ]]; then
    printf 'FAIL %s:\n%s\n' "$1" "$found"
    failures=$((failures + 1))
  else
    echo "ok   $1"
  fi
}

check "every image has a tag, and none is latest" '$4 !~ /:[^\/]+$/ || $4 ~ /:latest$/'
check "every container has a memory limit equal to its request, and a CPU request" \
  '$6 == "null" || $5 != $6 || $7 == "null"'
check "every long-running container has liveness and readiness probes" \
  '$1 !~ /^Job\// && $2 == "containers" && ($8 != "true" || $9 != "true")'
check "every pod runs as non-root with the default seccomp profile" \
  '$10 != "true" || $11 != "RuntimeDefault"'
check "no container can escalate privileges or keeps a capability" '$12 != "false" || $13 != "true"'
check "no pod gets service-link variables or a service account token" \
  '$14 != "false" || $15 != "false"'

secrets=""
for file in "${DEPLOYED[@]}"; do
  secrets+="$(yq 'select(.kind == "Secret") | "Secret/" + .metadata.name + " "' "/manifests/$file")"
done
if [[ -n "$secrets" ]]; then
  echo "FAIL no Secret objects in Git (the deploy creates them from .env): $secrets"
  failures=$((failures + 1))
else
  echo "ok   no Secret objects in Git"
fi

deployments=$(yq 'select(.kind == "Deployment") | .metadata.name' /manifests/overlays-kind.yaml | sort)
budgets=$(yq 'select(.kind == "PodDisruptionBudget") | .spec.selector.matchLabels["app.kubernetes.io/name"]' \
  /manifests/overlays-kind.yaml | sort)
unbudgeted=$(comm -23 <(echo "$deployments") <(echo "$budgets"))
if [[ -n "$unbudgeted" ]]; then
  echo "FAIL every Deployment has a PodDisruptionBudget; missing:" $unbudgeted
  failures=$((failures + 1))
else
  echo "ok   every Deployment has a PodDisruptionBudget"
fi

# Not manifests, but the same gate: readiness must not depend on Kafka, and the JVM must size its
# heap from the container's limit rather than the node's memory.
if grep -n -i -E '^\s*include:.*kafka' "$ROOT"/services/*/src/main/resources/application.yml; then
  echo "FAIL a health group includes Kafka (above)"
  failures=$((failures + 1))
else
  echo "ok   no health group includes Kafka"
fi
if grep -q 'MaxRAMPercentage' "$ROOT/deploy/docker/spring-boot-service.Dockerfile"; then
  echo "ok   the Spring Boot image sizes its heap from the container limit"
else
  echo "FAIL deploy/docker/spring-boot-service.Dockerfile does not set MaxRAMPercentage"
  failures=$((failures + 1))
fi

((failures == 0)) || die "$failures policy check(s) failed"
info "All Kubernetes checks passed"
