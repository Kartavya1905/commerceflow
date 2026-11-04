#!/usr/bin/env bash
# Demonstrates a bad release and `kubectl rollout undo` on kind (ROADMAP M10).
#
#   scripts/kind-up.sh        first
#   scripts/kind-rollback.sh  then (about 2 minutes)
#
# 1. Builds a broken catalog-service release: the running image, with an entrypoint that exits,
#    so its pods never become ready. Tagged as a new version, like any release.
# 2. Rolls it out. maxUnavailable is 0, so the old pod keeps serving while the new one fails.
# 3. `kubectl rollout undo` goes back to the previous version.
# Throughout, a probe reads the catalogue through the gateway five times a second. It passes when
# the previous image is running again and no request failed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

CONTEXT=kind-commerceflow

k() {
  kubectl --context "$CONTEXT" --namespace commerceflow "$@"
}

command -v kind > /dev/null || die "kind is needed"
k get deployment catalog-service > /dev/null 2>&1 || die "CommerceFlow is not running on kind; start it with scripts/kind-up.sh"
k rollout status deployment/catalog-service --timeout=120s > /dev/null

good=$(k get deployment catalog-service -o jsonpath='{.spec.template.spec.containers[0].image}')
bad="${good}-broken"
info "Running: $good"

info "Building the broken release $bad"
printf 'FROM %s\nENTRYPOINT ["sh", "-c", "echo this release fails to start >&2; exit 1"]\n' "$good" |
  docker build --quiet -t "$bad" - > /dev/null
kind load docker-image --name commerceflow "$bad" > /dev/null 2>&1

# The probe: one line per request, its status code (000 when nothing answered). The body goes to
# a real file: with Git Bash's path conversion off (common.sh), Windows' curl cannot open
# /dev/null, and would report a failure after every successful request.
probe_log="$(mktemp)"
probe_body="$ROOT/deploy/k8s/.generated/probe-body"
mkdir -p "$(dirname "$probe_body")"
trap 'kill "$probe_pid" 2> /dev/null || true; rm -f "$probe_log" "$probe_body"' EXIT
(
  while :; do
    curl -s -o "$probe_body" -w '%{http_code}\n' --max-time 2 \
      "http://localhost:18080/api/v1/products?size=5" >> "$probe_log" || echo 000 >> "$probe_log"
    sleep 0.2
  done
) &
probe_pid=$!

k annotate deployment/catalog-service kubernetes.io/change-cause="release ${bad##*:}" --overwrite > /dev/null
k set image deployment/catalog-service "catalog-service=$bad" > /dev/null
info "Rolled out the broken release; waiting 60 s for it to become ready (it will not)"
if k rollout status deployment/catalog-service --timeout=60s > /dev/null 2>&1; then
  die "the broken release became ready, so this demonstrates nothing"
fi
k get pods -l app.kubernetes.io/name=catalog-service \
  -o custom-columns=POD:.metadata.name,IMAGE:.spec.containers[0].image,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount

info "kubectl rollout undo"
# Undo is the emergency move. It leaves the cluster differing from Git until the next apply, which
# is where the real fix (a revert) then arrives; kubectl warns about exactly that.
k rollout undo deployment/catalog-service 2>&1 | grep -v -E '^Warning|rolled back' || true
k annotate deployment/catalog-service kubernetes.io/change-cause="rollback to ${good##*:}" \
  --overwrite > /dev/null
k rollout status deployment/catalog-service --timeout=180s > /dev/null
now=$(k get deployment catalog-service -o jsonpath='{.spec.template.spec.containers[0].image}')
sleep 2
kill "$probe_pid" 2> /dev/null || true
wait "$probe_pid" 2> /dev/null || true

total=$(wc -l < "$probe_log" | tr -d ' ')
failed=$(grep -vc '^200$' "$probe_log" || true)
info "Probe answers by status code"
tr -d '\r' < "$probe_log" | sort | uniq -c | sed 's/^/   /'
info "Revision history"
k rollout history deployment/catalog-service | tail -4
echo "    Image now: $now"
echo "    Probe: $total requests through the gateway, $failed failed"
[[ "$now" == "$good" ]] || die "the rollback did not restore $good"
((failed == 0)) || die "$failed request(s) failed during the bad release or the rollback"
info "Rolled back to the previous release with no failed request"
