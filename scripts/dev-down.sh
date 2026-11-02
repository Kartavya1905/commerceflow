#!/usr/bin/env bash
# Stops the CommerceFlow local environment.
#
#   scripts/dev-down.sh                  stop and remove containers; data is kept
#   scripts/dev-down.sh --volumes        also DELETE local data (databases, Kafka, Grafana); asks first
#   scripts/dev-down.sh --volumes --yes  same, without asking (for CI)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

volumes=false
assume_yes=false
for arg in "$@"; do
  case "$arg" in
    --volumes) volumes=true ;;
    --yes) assume_yes=true ;;
    -h | --help)
      sed -n '2,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) die "unknown option: $arg (try --help)" ;;
  esac
done

require_docker
[[ -f "$ENV_FILE" ]] || touch "$ENV_FILE"

args=(--profile obs --profile apps down --remove-orphans)
if $volumes; then
  if ! $assume_yes; then
    read -r -p "Delete all local CommerceFlow data (databases, Kafka topics, Grafana)? [y/N] " answer
    [[ "$answer" =~ ^[Yy]$ ]] || die "cancelled"
  fi
  args+=(--volumes)
fi

compose "${args[@]}"
info "Stopped$($volumes && echo ' and deleted local data' || true)"
