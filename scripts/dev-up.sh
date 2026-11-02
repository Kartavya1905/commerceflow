#!/usr/bin/env bash
# Starts the CommerceFlow local environment and waits until it is healthy.
#
#   scripts/dev-up.sh          core stack: Postgres, Kafka (+ topics), Redis, Mailpit
#   scripts/dev-up.sh --obs    core + OpenTelemetry Collector, Prometheus, Tempo, Loki, Grafana
#
# First run creates .env from .env.example with random local passwords.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

profiles=()
for arg in "$@"; do
  case "$arg" in
    --obs) profiles+=(--profile obs) ;;
    -h | --help)
      sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) die "unknown option: $arg (try --help)" ;;
  esac
done

# --- 1. .env: create it, or add keys that appeared in .env.example since it was created --------
ensure_env_file() {
  if [[ ! -f "$ENV_FILE" ]]; then
    : > "$ENV_FILE"
    info "Creating .env with random local passwords (git-ignored)"
  fi

  local line key value added=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^([A-Z0-9_]+)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    grep -q "^$key=" "$ENV_FILE" && continue
    [[ "$value" == CHANGE_ME* ]] && value="$(random_secret)"
    echo "$key=$value" >> "$ENV_FILE"
    added=$((added + 1))
  done < "$ROOT/.env.example"
  ((added == 0)) || info "Added $added setting(s) to .env"

  local placeholders
  placeholders=$(grep -E '^[A-Z0-9_]+=CHANGE_ME' "$ENV_FILE" | cut -d= -f1 | tr '\n' ' ' || true)
  [[ -z "$placeholders" ]] || die ".env still has placeholder values for: $placeholders"
}

# --- 2. Ports: fail early with a clear message instead of a cryptic bind error -----------------
check_ports() {
  local ports=(5432 9092 6379 1025 8025)
  [[ ${#profiles[@]} -eq 0 ]] || ports+=(4317 4318 9090 3000)

  # `docker port` lists each mapping on its own line ("4317/tcp -> 127.0.0.1:4317"), unlike
  # `docker ps`, which condenses neighbours into ranges such as 4317-4318.
  local ours="" id busy=()
  for id in $(docker ps -q --filter "label=com.docker.compose.project=$PROJECT"); do
    ours+="$(docker port "$id")"$'\n'
  done
  for port in "${ports[@]}"; do
    if port_in_use "$port" && ! grep -q "127.0.0.1:$port\$" <<< "$ours"; then
      busy+=("$port")
    fi
  done
  [[ ${#busy[@]} -eq 0 ]] ||
    die "port(s) ${busy[*]} are already used by another program (see docs/runbooks/infra.md#port-already-in-use)."
}

# --- 3. Start and wait -------------------------------------------------------------------------
require_docker
ensure_env_file
check_ports

# `docker compose up --wait` treats a one-shot container that exits 0 as a failure, so wait here
# instead: services with a health check must be healthy, others running, and init jobs exited 0.
JOBS="postgres-init kafka-init"
wait_for_stack() {
  local deadline=$((SECONDS + 240)) pending service state health exit_code
  while :; do
    pending=()
    # '|' as separator: Health is often empty, and whitespace splitting would shift the fields.
    while IFS='|' read -r service state health exit_code; do
      if [[ " $JOBS " == *" $service "* ]]; then
        if [[ "$state" == exited && "$exit_code" != 0 ]]; then
          compose logs --no-color --tail 40 "$service" >&2 || true
          die "$service failed (exit code $exit_code)"
        fi
        [[ "$state" == exited ]] || pending+=("$service")
      elif [[ "$state" != running ]]; then
        compose logs --no-color --tail 40 "$service" >&2 || true
        die "$service is $state"
      elif [[ -n "$health" && "$health" != healthy ]]; then
        pending+=("$service($health)")
      fi
    done < <(compose "${profiles[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}')

    [[ ${#pending[@]} -eq 0 ]] && return 0
    ((SECONDS < deadline)) || die "timed out waiting for: ${pending[*]}"
    sleep 3
  done
}

info "Starting containers (first run builds kafka-init and can take a few minutes)"
compose "${profiles[@]}" up -d --build --remove-orphans
wait_for_stack

info "Ready"
cat << EOF
    Postgres     localhost:5432   (one database and role per service; passwords in .env)
    Kafka        localhost:9092   (containers use kafka:29092)
    Redis        localhost:6379
    Mailpit      http://localhost:8025   (SMTP on localhost:1025)
EOF
if [[ ${#profiles[@]} -gt 0 ]]; then
  cat << EOF
    Grafana      http://localhost:3000   (user: $(env_value GRAFANA_ADMIN_USER), password: GRAFANA_ADMIN_PASSWORD in .env)
    Prometheus   http://localhost:9090
    OTLP         localhost:4317 (gRPC), localhost:4318 (HTTP)
EOF
fi
echo "    Verify with: scripts/check-infra.sh"
