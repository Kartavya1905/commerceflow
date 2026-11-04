#!/usr/bin/env bash
# Deletes the local kind cluster and everything in it (its databases included). The images stay
# on this machine, so the next scripts/kind-up.sh --no-build is quick.
#
#   scripts/kind-down.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

command -v kind > /dev/null || die "kind is not installed, so there is no cluster to delete"
if kind get clusters 2> /dev/null | grep -qx commerceflow; then
  kind delete cluster --name commerceflow
else
  info "No commerceflow cluster is running"
fi
