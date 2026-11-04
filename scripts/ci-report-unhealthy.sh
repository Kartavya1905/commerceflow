#!/usr/bin/env bash
# Run by CI when the stack job fails: every container that is not healthy (or an init job that did
# not exit 0) becomes a GitHub annotation with its state and last log lines. Annotations show on
# the run page and through the public API, so a failure can be read without opening the job log.
#
# Usage: scripts/ci-report-unhealthy.sh [dev-up output file]
#   With the file, its last lines become an annotation too (a failure before any container ran).
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

profiles=(--profile obs --profile apps)
JOBS="postgres-init kafka-init"

# GitHub workflow commands end at a newline, so encode the characters they treat as special.
encode() {
  local text="$1"
  text="${text//'%'/'%25'}"
  text="${text//$'\r'/'%0D'}"
  text="${text//$'\n'/'%0A'}"
  printf '%s' "$text"
}

found=0
while IFS='|' read -r service state health exit_code; do
  bad=false
  if [[ " $JOBS " == *" $service "* ]]; then
    [[ "$state" == exited && "$exit_code" == 0 ]] || bad=true
  elif [[ "$state" != running || (-n "$health" && "$health" != healthy) ]]; then
    bad=true
  fi
  $bad || continue
  found=$((found + 1))
  lines="$(compose "${profiles[@]}" logs --no-color --no-log-prefix --tail 15 "$service" 2>&1 | cut -c1-400)"
  echo "::error title=${service} is ${state}${health:+/$health} (exit ${exit_code})::$(encode "$lines")"
done < <(compose "${profiles[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}')

echo "$found unhealthy container(s)"

if [[ -n "${1:-}" && -s "$1" ]]; then
  echo "::error title=dev-up output (last lines)::$(encode "$(tail -n 20 "$1" | cut -c1-400)")"
fi
