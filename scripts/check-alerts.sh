#!/usr/bin/env bash
# Checks the Prometheus alert rules: every alert links a runbook section, and that section exists
# (ROADMAP Milestone 9: "alerts without a runbook -> link required"). Then runs the promtool unit
# tests in deploy/compose/prometheus/alerts.test.yml, which fire and clear every alert.
#
#   scripts/check-alerts.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

RULES="$ROOT/deploy/compose/prometheus/alerts.yml"
RUNBOOK="$ROOT/docs/runbooks/infra.md"
PROMETHEUS_IMAGE=prom/prometheus:v3.15.1

# GitHub's heading anchors: lowercase, punctuation dropped, spaces to dashes.
anchors=$(sed -n 's/^#\{2,4\} //p' "$RUNBOOK" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9 -]//g' -e 's/ /-/g')

failed=0
alerts=0
while IFS= read -r name; do
  alerts=$((alerts + 1))
  # The runbook line that belongs to this alert: the first one after its "- alert:" line.
  link=$(awk -v name="$name" '
    $0 ~ "- alert: "name"$" { found = 1; next }
    found && /- alert:/ { exit }
    found && /runbook:/ { sub(/.*runbook: */, ""); print; exit }' "$RULES")
  if [[ -z "$link" ]]; then
    echo "FAIL  $name has no runbook link"
    failed=1
  elif [[ "$link" != docs/runbooks/infra.md#* ]] || ! grep -qx "${link#*#}" <<< "$anchors"; then
    echo "FAIL  $name links $link, which is not a heading in docs/runbooks/infra.md"
    failed=1
  fi
done < <(sed -n 's/^ *- alert: //p' "$RULES")
[[ $failed -eq 0 ]] && info "all $alerts alerts link an existing runbook section"

require_docker
docker run --rm --entrypoint promtool -v "$ROOT/deploy/compose/prometheus:/p:ro" "$PROMETHEUS_IMAGE" \
  check rules /p/alerts.yml > /dev/null || failed=1
docker run --rm --entrypoint promtool -v "$ROOT/deploy/compose/prometheus:/p:ro" "$PROMETHEUS_IMAGE" \
  test rules /p/alerts.test.yml || failed=1

exit $failed
