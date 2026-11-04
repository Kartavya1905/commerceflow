#!/usr/bin/env bash
# Starts the CommerceFlow local environment and waits until it is healthy.
#
#   scripts/dev-up.sh                core stack: Postgres, Kafka (+ topics), Redis, Mailpit
#   scripts/dev-up.sh --obs          core + OpenTelemetry Collector, Prometheus, Tempo, Loki, Grafana
#   scripts/dev-up.sh --apps         core + the services (builds their jars first)
#   scripts/dev-up.sh --obs --apps   everything
#   scripts/dev-up.sh --apps --chaos the services behind Toxiproxy, with fault injection wired
#                                    up (off until set) for the failure-recovery system tests
#
# First run creates .env from .env.example with random local passwords, and a JWT signing key in
# the git-ignored secrets/ folder.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

profiles=()
with_apps=false
with_obs=false
for arg in "$@"; do
  case "$arg" in
    --obs)
      profiles+=(--profile obs)
      with_obs=true
      # The collector runs, so the services can send it their traces.
      export TRACING_EXPORT=true
      ;;
    --apps)
      profiles+=(--profile apps)
      with_apps=true
      ;;
    --chaos)
      export COMMERCEFLOW_CHAOS=1
      ;;
    -h | --help)
      sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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
  ! $with_obs || ports+=(4317 4318 9090 3000)
  ! $with_apps || ports+=(8000 8080 8081 8082 8083 8084 8085)

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

# --- 3. JWT signing key: a local throwaway key, never committed ---------------------------------
ensure_jwt_key() {
  local key="$ROOT/secrets/jwt-private.pem"
  [[ -s "$key" ]] && return 0
  command -v openssl > /dev/null 2>&1 ||
    die "openssl is needed to create the local JWT key (Git for Windows and Linux include it)"
  mkdir -p "$ROOT/secrets"
  info "Creating a local JWT signing key in secrets/ (git-ignored)"
  # genpkey writes PKCS#8, the format identity-service expects.
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key" 2> /dev/null
  # The container runs as an unprivileged user (uid 10001) and reads this through a bind mount,
  # so it must be readable by others. Acceptable for a local development key only; a real
  # deployment uses a secret store (Kubernetes Secret, AWS Secrets Manager).
  chmod 0644 "$key"
}

# --- 4. Service jars, built on the host and copied into images ---------------------------------
build_jars() {
  info "Building service jars (tests are run separately with ./mvnw verify)"
  # mvnw is a bash script that hands /c/... classpath entries to the Windows java.exe, which
  # needs Git Bash's path conversion. common.sh turns that off for docker, so undo it here.
  # forceCreation: a service whose own classes did not change would otherwise keep its old jar,
  # with the old copies of libraries that did change (and Spring Boot skips repackaging it).
  (cd "$ROOT" && env -u MSYS_NO_PATHCONV -u MSYS2_ARG_CONV_EXCL ./mvnw -B -ntp -q package \
    -DskipTests -Dmaven.jar.forceCreation=true -pl services/identity-service,services/catalog-service,services/order-service,services/inventory-service,services/payment-sandbox,services/payment-service,services/api-gateway -am) ||
    die "the Maven build failed; run ./mvnw package -DskipTests to see why"
}

# --- 5. Start and wait -------------------------------------------------------------------------
require_docker
ensure_env_file
ensure_jwt_key
check_ports
! $with_apps || build_jars

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
    if ((SECONDS >= deadline)); then
      # What each one was doing when time ran out, so the failure explains itself (in CI too).
      for service in "${pending[@]}"; do
        compose "${profiles[@]}" logs --no-color --tail 40 "${service%%(*}" >&2 || true
      done
      die "timed out waiting for: ${pending[*]}"
    fi
    sleep 3
  done
}

info "Starting containers (first run builds kafka-init and can take a few minutes)"
compose "${profiles[@]}" up -d --build --remove-orphans
wait_for_stack

# Config files are bind-mounted, and `up` does not restart a container when only a mounted file
# changed. Prometheus can reload in place, so edits to its scrape config apply on the next dev-up.
if $with_obs; then
  curl -fsS -X POST --max-time 5 http://127.0.0.1:9090/-/reload > /dev/null ||
    echo "warning: could not reload Prometheus config; restart it with: docker compose restart prometheus" >&2
fi

info "Ready"
cat << EOF
    Postgres     localhost:5432   (one database and role per service; passwords in .env)
    Kafka        localhost:9092   (containers use kafka:29092)
    Redis        localhost:6379
    Mailpit      http://localhost:8025   (SMTP on localhost:1025)
EOF
if $with_obs; then
  cat << EOF
    Grafana      http://localhost:3000   (user: $(env_value GRAFANA_ADMIN_USER), password: GRAFANA_ADMIN_PASSWORD in .env)
    Prometheus   http://localhost:9090
    OTLP         localhost:4317 (gRPC), localhost:4318 (HTTP)
EOF
fi
if $with_apps; then
  cat << EOF
    Web app      http://localhost:8000   (admin: $(env_value BOOTSTRAP_ADMIN_EMAIL), password: BOOTSTRAP_ADMIN_PASSWORD in .env)
    API gateway  http://localhost:8080
    Services     identity :8081, catalog :8082, order :8083, inventory :8084, payment :8085 (direct, for debugging)
EOF
fi
echo "    Verify with: scripts/check-infra.sh"
