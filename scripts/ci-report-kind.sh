#!/usr/bin/env bash
# Run by CI when the kind job fails: every pod that is neither ready nor a finished Job becomes a
# GitHub annotation with its phase and last log lines, so the failure can be read on the run page
# and through the public API, without opening the job log.
#
# Usage: scripts/ci-report-kind.sh [kind-up output file]
#   With the file, its last lines become an annotation too (a failure before any pod ran).
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

k() {
  kubectl --context kind-commerceflow --namespace commerceflow "$@"
}

# GitHub workflow commands end at a newline, so encode the characters they treat as special.
encode() {
  local text="$1"
  text="${text//'%'/'%25'}"
  text="${text//$'\r'/'%0D'}"
  text="${text//$'\n'/'%0A'}"
  printf '%s' "$text"
}

found=0
# name|phase|ready flags of its containers
while IFS='|' read -r pod phase ready; do
  [[ -n "$pod" ]] || continue
  [[ "$phase" == Succeeded ]] && continue
  [[ "$phase" == Running && "$ready" != *false* ]] && continue
  found=$((found + 1))
  lines="$(k logs "$pod" --all-containers --tail 15 2>&1 | cut -c1-400)"
  echo "::error title=${pod} is ${phase} (ready: ${ready:-none})::$(encode "$lines")"
done < <(k get pods -o jsonpath='{range .items[*]}{.metadata.name}|{.status.phase}|{.status.containerStatuses[*].ready}{"\n"}{end}' 2> /dev/null)

echo "$found failing pod(s)"

if [[ -n "${1:-}" && -s "$1" ]]; then
  echo "::error title=kind-up output (last lines)::$(encode "$(tail -n 20 "$1" | cut -c1-400)")"
fi
