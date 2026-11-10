#!/usr/bin/env bash
# Runs a Milestone 12 performance workload against the Compose stack, and keeps everything a
# report needs: the raw k6 summary, the environment, and resource use over time.
#
#   scripts/dev-up.sh --obs --apps     first: the whole stack
#   scripts/perf.sh smoke|load|stress|soak [--reseed]
#
# 1. Raises the gateway's per-client rate limits for the run, and restores them when it ends: every
#    virtual user comes from one address and would otherwise share one bucket.
# 2. Seeds the data set once (load-tests/k6/perf-seed.js; --reseed adds another): 1,000 products,
#    100 customers, 500 confirmed orders. The customers' sign-ins stay in the git-ignored secrets/.
# 3. Records the environment: hardware, Docker's share of it, versions, commit, data set size.
# 4. Samples `docker stats` every 10 s, k6 included: k6 runs on the same machine as the system
#    (capped at K6_CPUS, default 2), and the report says how much CPU it took.
# 5. Runs load-tests/k6/perf-reads.js with PROFILE, and keeps its summary, passing or not: the M12
#    gate asks for the first failing result too, not only the last passing one.
#
# Everything lands in load-tests/k6/results/<time>-<profile>/ (git-ignored); the summary, the
# environment and the samples are also copied to docs/reports/data/perf/ for the report.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

K6_IMAGE=grafana/k6:2.3.0
K6_CPUS="${K6_CPUS:-2}"
NETWORK=commerceflow_default
USERS_FILE="$ROOT/secrets/perf-users.json"

profile="${1:-}"
reseed=false
[[ "${2:-}" != --reseed ]] || reseed=true
case "$profile" in
  smoke | load | stress | soak) ;;
  *) die "usage: scripts/perf.sh smoke|load|stress|soak [--reseed]" ;;
esac

require_docker
[[ "$(docker inspect -f '{{.State.Health.Status}}' commerceflow-api-gateway-1 2> /dev/null)" == healthy ]] ||
  die "the stack is not running; start it with scripts/dev-up.sh --obs --apps"

stamp="$(date +%Y%m%d-%H%M%S)"
results="$ROOT/load-tests/k6/results/$stamp-$profile"
mkdir -p "$results"

gateway_healthy() {
  for _ in $(seq 1 60); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' commerceflow-api-gateway-1)" == healthy ]] && return 0
    sleep 3
  done
  die "the gateway did not become healthy"
}

yq() {
  docker run --rm -i mikefarah/yq:4.53.6 -p json "$@"
}

# The services export traces when the stack runs with the collector (dev-up --obs); the recreated
# gateway does the same, so the run measures the stack as it is deployed.
tracing=false
docker ps --format '{{.Names}}' | grep -qx commerceflow-otel-collector-1 && tracing=true
export TRACING_EXPORT="$tracing"

# --- 1. Rate limits up for the run, back down after ------------------------------------------
info "Raising the gateway's per-client limits for the run"
GATEWAY_RATE=1000000 GATEWAY_BURST=1000000 GATEWAY_AUTH_RATE=100000 GATEWAY_AUTH_BURST=100000 \
  compose --profile obs --profile apps up -d --no-deps api-gateway > /dev/null 2>&1
restore_limits() {
  info "Restoring the gateway's per-client limits"
  compose --profile obs --profile apps up -d --no-deps api-gateway > /dev/null 2>&1 || true
}
trap 'kill "${sampler:-}" 2> /dev/null || true; restore_limits' EXIT
gateway_healthy
# The gateway was just recreated: a fresh JVM's first requests took up to 94 s in the first smoke
# run (its connection pools and JIT start cold). Warm it with a few hundred reads, not measured.
info "Warming up the recreated gateway (not measured)"
docker run --rm --network "$NETWORK" --entrypoint sh curlimages/curl:8.17.0 -c \
  'for i in $(seq 1 300); do curl -s -o /dev/null --max-time 30 "http://api-gateway:8080/api/v1/products?size=20&page=$((i % 10))"; done'

# --- 2. The data set -----------------------------------------------------------------------------
if $reseed || [[ ! -s "$USERS_FILE" ]]; then
  info "Seeding the data set (several minutes: every order goes through the saga)"
  mkdir -p "$ROOT/secrets"
  ADMIN_EMAIL="$(env_value BOOTSTRAP_ADMIN_EMAIL)" ADMIN_PASSWORD="$(env_value BOOTSTRAP_ADMIN_PASSWORD)" \
    docker run --rm --network "$NETWORK" -e ADMIN_EMAIL -e ADMIN_PASSWORD \
    -v "$ROOT/load-tests/k6:/scripts:ro" -v "$ROOT/secrets:/out" \
    "$K6_IMAGE" run --quiet /scripts/perf-seed.js
fi

# --- 3. The environment --------------------------------------------------------------------------
products=$(docker run --rm --network "$NETWORK" curlimages/curl:8.17.0 -s \
  "http://api-gateway:8080/api/v1/products?size=1" | grep -o '"totalItems":[0-9]*' | cut -d: -f2)
cpu_name=$( (powershell.exe -NoProfile -Command "(Get-CimInstance Win32_Processor).Name" 2> /dev/null ||
  grep -m1 'model name' /proc/cpuinfo | cut -d: -f2) | tr -d '\r' | sed 's/^ *//; s/ *$//')
{
  echo "{"
  echo "  \"started\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo "  \"profile\": \"$profile\","
  echo "  \"commit\": \"$(git -C "$ROOT" rev-parse --short=12 HEAD)$([[ -z "$(git -C "$ROOT" status --porcelain)" ]] || echo "-dirty")\","
  echo "  \"cpu\": \"$cpu_name\","
  echo "  \"docker\": \"$(docker version --format '{{.Server.Version}}'), $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')\","
  echo "  \"k6\": \"$K6_IMAGE, capped at $K6_CPUS CPUs, on the same machine and Docker as the system\","
  echo "  \"products\": ${products:-null},"
  echo "  \"customers\": $(yq '.users | length' < "$USERS_FILE"),"
  echo "  \"seeded_orders\": $(yq '[.users[].orders[]] | length' < "$USERS_FILE"),"
  echo "  \"tracing\": \"export $tracing, sampling ${TRACING_SAMPLING:-1.0} of requests\","
  echo "  \"jvm_options\": \"$(docker exec commerceflow-api-gateway-1 printenv JAVA_TOOL_OPTIONS)\","
  echo "  \"other_containers\": $(docker ps --format '{{.Names}}' | grep -cv '^commerceflow-'),"
  echo "  \"gateway_rate_limits\": \"raised for the run (one client address)\""
  echo "}"
} > "$results/environment.json"

# --- 4. Resource use, every 10 s -------------------------------------------------------------------
(
  echo "time,container,cpu_percent,memory"
  while :; do
    now=$(date -u +%H:%M:%S)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2> /dev/null |
      grep -E '^(commerceflow-|k6-perf)' | sed "s/^/$now,/"
    sleep 10
  done
) > "$results/docker-stats.csv" &
sampler=$!

# --- 5. The run ------------------------------------------------------------------------------------
info "Running $profile (k6 capped at $K6_CPUS CPUs)"
status=0
docker run --rm --name k6-perf --cpus "$K6_CPUS" --network "$NETWORK" -e PROFILE="$profile" \
  -v "$ROOT/load-tests/k6:/scripts:ro" -v "$USERS_FILE:/data/perf-users.json:ro" -v "$results:/out" \
  "$K6_IMAGE" run --quiet /scripts/perf-reads.js | tee "$results/summary.txt" || status=$?
kill "$sampler" 2> /dev/null || true

kept="$ROOT/docs/reports/data/perf/$stamp-$profile"
mkdir -p "$kept"
cp "$results/summary.json" "$results/summary.txt" "$results/environment.json" "$results/docker-stats.csv" "$kept/"
info "Kept in ${kept#"$ROOT"/} (k6 exit $status: 0 all thresholds met, 99 some failed)"
