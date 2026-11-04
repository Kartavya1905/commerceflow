#!/usr/bin/env bash
# The HPA experiment (ROADMAP M10): a fixed rate of reads on catalog-service (RATE, default 200
# per second, for HOLD, default 4m), then nothing, while recording what its HorizontalPodAutoscaler
# does.
#
#   scripts/kind-up.sh       first
#   scripts/kind-hpa.sh      then (about 10 minutes)
#
# Runs load-tests/k6/catalog-hpa.js as a Job inside the cluster, and every 15 s records the time,
# the pods' average CPU against their request (what the autoscaler sees), the desired and current
# replica counts, and the ready pods. It keeps recording after the load stops, until catalog is
# back at its minimum. The samples go to load-tests/k6/results/hpa-<time>.tsv (git-ignored), and
# k6's summary is printed at the end.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

K6_IMAGE=grafana/k6:2.3.0
CONTEXT=kind-commerceflow
RATE="${RATE:-200}"
HOLD="${HOLD:-4m}"

k() {
  kubectl --context "$CONTEXT" --namespace commerceflow "$@"
}

command -v kind > /dev/null || die "kind is needed"
k get hpa catalog-service > /dev/null 2>&1 || die "CommerceFlow is not running on kind; start it with scripts/kind-up.sh"

kind_load_public commerceflow "$K6_IMAGE"

min=$(k get hpa catalog-service -o jsonpath='{.spec.minReplicas}')
# Start from rest: the minimum, and the metrics pipeline reporting.
k rollout status deployment/catalog-service --timeout=120s > /dev/null
info "Waiting for metrics-server to report catalog's CPU"
for _ in $(seq 1 40); do
  k get hpa catalog-service -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' |
    grep -q '[0-9]' && break
  sleep 5
done

k create configmap k6-catalog-hpa --from-file=script.js="$ROOT/load-tests/k6/catalog-hpa.js" \
  --dry-run=client -o yaml | k apply -f - > /dev/null
k delete job k6-catalog-hpa --ignore-not-found > /dev/null
k apply -f - > /dev/null << EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: k6-catalog-hpa
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      enableServiceLinks: false
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 12345
        runAsGroup: 12345
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: k6
          image: $K6_IMAGE
          args: [run, --quiet, /scripts/script.js]
          env:
            - { name: RATE, value: "$RATE" }
            - { name: HOLD, value: "$HOLD" }
          resources:
            requests: { cpu: "1", memory: 512Mi }
            limits: { memory: 512Mi }
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: [ALL] }
          volumeMounts:
            - { name: script, mountPath: /scripts, readOnly: true }
      volumes:
        - name: script
          configMap: { name: k6-catalog-hpa }
EOF

mkdir -p "$ROOT/load-tests/k6/results"
samples="$ROOT/load-tests/k6/results/hpa-$(date +%Y%m%d-%H%M%S).tsv"
printf 'seconds\tcpu_percent_of_request\tdesired\tcurrent\tready\tload\n' > "$samples"
info "Load: $RATE requests per second for $HOLD; sampling every 15 s into ${samples#"$ROOT"/}"
start=$SECONDS
load_ended=""
while :; do
  hpa=$(k get hpa catalog-service -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}|{.status.desiredReplicas}|{.status.currentReplicas}')
  ready=$(k get deployment catalog-service -o jsonpath='{.status.readyReplicas}')
  job=$(k get job k6-catalog-hpa -o jsonpath='{.status.active}')
  load=on
  [[ "${job:-0}" -ge 1 ]] || load=off
  IFS='|' read -r cpu desired current <<< "$hpa"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$((SECONDS - start))" "${cpu:--}" "${desired:--}" "${current:--}" \
    "${ready:-0}" "$load" | tee -a "$samples"
  if [[ "$load" == off ]]; then
    load_ended="${load_ended:-$SECONDS}"
    # Done once it is back at the minimum, or after 6 minutes without load.
    [[ "${current:-0}" -le "$min" && "${ready:-0}" -le "$min" ]] && break
    ((SECONDS - load_ended < 360)) || break
  fi
  sleep 15
done

info "k6 summary"
k logs job/k6-catalog-hpa | grep -E 'http_req_duration|http_req_failed|http_reqs|checks|iterations|vus_max|✓|✗' || true
# A load generator that died (out of memory, say) ends the load early and silently: say so.
succeeded=$(k get job k6-catalog-hpa -o jsonpath='{.status.succeeded}')
if [[ "${succeeded:-0}" -lt 1 ]]; then
  reason=$(k get pods -l job-name=k6-catalog-hpa -o jsonpath='{.items[0].status.containerStatuses[0].state.terminated.reason}')
  die "the load generator did not finish (${reason:-unknown}; a failed k6 threshold also ends here), so these numbers are not a complete run"
fi
k get events --field-selector involvedObject.name=catalog-service \
  --sort-by=.lastTimestamp -o custom-columns=TIME:.lastTimestamp,REASON:.reason,MESSAGE:.message |
  grep -i -E 'rescale|scaled' || true
info "Samples: ${samples#"$ROOT"/}"
