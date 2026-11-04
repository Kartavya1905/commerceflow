#!/usr/bin/env bash
# Security scans, the same locally and in CI (ROADMAP Milestone 9), with no installs: each tool
# runs in its pinned image.
#
#   scripts/scan-security.sh secrets   gitleaks over the whole git history
#   scripts/scan-security.sh fs        Trivy over the repository: npm dependencies,
#                                      Dockerfile and Compose misconfiguration, secrets
#   scripts/scan-security.sh images    Trivy over the built service images (dev-up --apps first)
#   scripts/scan-security.sh all
#
# Fails on any HIGH or CRITICAL finding that has a fix and is not triaged in .trivyignore, where
# every entry says why it is accepted and until when.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

TRIVY_IMAGE=aquasec/trivy:0.75.0
GITLEAKS_IMAGE=zricethezav/gitleaks:v8.30.1
IMAGES=(identity-service catalog-service order-service inventory-service payment-service
  payment-sandbox api-gateway web)

require_docker
failed=0

# Trivy's vulnerability database is cached in a named volume, not downloaded on every run.
trivy() {
  docker run --rm -v commerceflow-trivy-cache:/root/.cache/trivy -v "$ROOT:/repo:ro" -w /repo \
    "$@"
}

secrets() {
  info "gitleaks: the whole git history"
  docker run --rm -v "$ROOT:/repo:ro" "$GITLEAKS_IMAGE" git /repo --no-banner --redact \
    --config /repo/.gitleaks.toml || failed=1
}

filesystem() {
  info "Trivy: npm dependencies, Dockerfile and Compose misconfiguration, secrets"
  # Java dependencies are scanned in the images, from the exact jars each service ships. Reading
  # them from pom.xml here would download the whole dependency tree from Maven Central, which
  # rate-limits a scan like that (HTTP 429) and adds nothing the image scan does not already cover.
  # secrets/ and .env are git-ignored and exist only on a developer's machine, by design; gitleaks
  # checks that no secret was ever committed.
  trivy "$TRIVY_IMAGE" fs --scanners vuln,misconfig,secret --severity HIGH,CRITICAL \
    --skip-version-check --skip-files '**/pom.xml' \
    --ignore-unfixed --ignorefile /repo/.trivyignore --timeout 20m --skip-dirs /repo/.git \
    --skip-dirs /repo/frontend/node_modules --skip-dirs /repo/frontend/dist \
    --skip-dirs /repo/secrets --skip-files /repo/.env \
    --skip-dirs '/repo/**/target' --exit-code 1 --no-progress /repo || failed=1
}

images() {
  for image in "${IMAGES[@]}"; do
    info "Trivy: commerceflow/$image:local"
    docker run --rm -v commerceflow-trivy-cache:/root/.cache/trivy \
      -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT/.trivyignore:/.trivyignore:ro" \
      "$TRIVY_IMAGE" image --skip-version-check --severity HIGH,CRITICAL --ignore-unfixed \
      --ignorefile /.trivyignore \
      --exit-code 1 --no-progress "commerceflow/$image:local" || failed=1
  done
}

case "${1:-all}" in
  secrets) secrets ;;
  fs) filesystem ;;
  images) images ;;
  all)
    secrets
    filesystem
    images
    ;;
  *) die "usage: scripts/scan-security.sh [secrets|fs|images|all]" ;;
esac
exit $failed
