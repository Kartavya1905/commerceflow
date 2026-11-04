#!/usr/bin/env bash
# Kills pods in the middle of checkouts on kind, and checks nothing was lost or doubled
# (ROADMAP M10: "kill a pod during checkout").
#
#   scripts/kind-up.sh       first
#   scripts/kind-chaos.sh    then (about a minute; CHECKOUTS and VUS change the load)
#
# load-tests/k6/checkout-under-failure.js places checkouts through the gateway, retrying each one
# with its own Idempotency-Key as the web app does. Ten seconds in, two failures at once:
# - one order-service pod hangs: its process is frozen on the node (SIGSTOP), so connections are
#   accepted and never answered. Readiness takes it out of the Service after two failed probes
#   (about 10 s), and liveness restarts it after three (about 30 s). Checkouts sent to it in the
#   meantime time out on the client and are retried with the same key: the case keys exist for.
#   (A plain crash is gentler: the kubelet sees the container exit and takes the pod out of the
#   Service at once, so at this load no request reached a crashed pod.)
# - the only payment-service pod is deleted, mid-saga.
# The test passes when every checkout was accepted, exactly one order exists per checkout (no retry
# made a second one), and every order is CONFIRMED.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

K6_IMAGE=grafana/k6:2.3.0
CONTEXT=kind-commerceflow
NODE=commerceflow-control-plane
CHECKOUTS="${CHECKOUTS:-90}"
VUS="${VUS:-3}"

k() {
  kubectl --context "$CONTEXT" --namespace commerceflow "$@"
}

require_docker
k get deployment order-service > /dev/null 2>&1 || die "CommerceFlow is not running on kind; start it with scripts/kind-up.sh"
port_in_use 18080 || die "the gateway is not reachable on localhost:18080"
k wait --for=condition=ready pod -l 'app.kubernetes.io/name in (order-service,payment-service)' \
  --timeout=180s > /dev/null

email=$(env_value BOOTSTRAP_ADMIN_EMAIL)
password=$(env_value BOOTSTRAP_ADMIN_PASSWORD)

info "Placing $CHECKOUTS checkouts through the gateway ($VUS at a time)"
# Credentials go in through the environment, never on the command line.
ADMIN_EMAIL="$email" ADMIN_PASSWORD="$password" docker run --rm -i --network host \
  -e ADMIN_EMAIL -e ADMIN_PASSWORD -e BASE_URL=http://localhost:18080 \
  -e CHECKOUTS="$CHECKOUTS" -e VUS="$VUS" \
  "$K6_IMAGE" run --quiet - < "$ROOT/load-tests/k6/checkout-under-failure.js" &
k6_pid=$!

sleep 10
hung=$(k get pods -l app.kubernetes.io/name=order-service -o jsonpath='{.items[0].metadata.name}')
container=$(docker exec "$NODE" crictl ps -q --name order-service --label "io.kubernetes.pod.name=$hung")
pid=$(docker exec "$NODE" crictl inspect --output go-template --template '{{.info.pid}}' "$container")
payment=$(k get pods -l app.kubernetes.io/name=payment-service -o jsonpath='{.items[0].metadata.name}')
restarts_before=$(k get pod "$hung" -o jsonpath='{.status.containerStatuses[0].restartCount}')
info "Freezing order-service in $hung, deleting $payment"
docker exec "$NODE" kill -STOP "$pid"
k delete pod "$payment" --wait=false > /dev/null

status=0
wait "$k6_pid" || status=$?
# Liveness gives up on the hung pod after about 30 s. A frozen process cannot receive SIGTERM, so
# the kubelet's SIGKILL at the end of the 40 s grace period is what ends it: allow two minutes.
info "Waiting for liveness to restart the hung pod"
for _ in $(seq 1 24); do
  restarts_after=$(k get pod "$hung" -o jsonpath='{.status.containerStatuses[0].restartCount}')
  ((restarts_after > restarts_before)) && break
  sleep 5
done
info "After the test"
k get pods -l 'app.kubernetes.io/name in (order-service,payment-service)'
echo "    $hung restarts: $restarts_before before, $restarts_after after (liveness)"
((status == 0)) || die "checkouts did not survive the failures (k6 exit $status)"
((restarts_after > restarts_before)) || die "liveness never restarted the hung pod"
info "Every checkout accepted once and confirmed, with a hung pod and a deleted pod mid-run"
