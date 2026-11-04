#!/usr/bin/env bash
# Shared helpers for the scripts in scripts/. Source it; do not run it.

# Git Bash on Windows rewrites arguments that look like paths (/opt/kafka/...) before they reach
# docker. Turn that off for everything these scripts run.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

# With path conversion off, docker.exe must receive Windows-style paths (C:/Users/...), which
# `pwd -W` gives on Git Bash. Elsewhere `pwd -W` is not an option, so fall back to `pwd`.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && { pwd -W 2> /dev/null || pwd; })"
COMPOSE_FILE="$ROOT/deploy/compose/docker-compose.yml"
ENV_FILE="$ROOT/.env"
# shellcheck disable=SC2034  # used by the scripts that source this file
PROJECT=commerceflow

die() {
  echo "error: $*" >&2
  exit 1
}

info() {
  echo "==> $*"
}

CHAOS_FILE="$ROOT/deploy/compose/chaos.yml"

# With COMMERCEFLOW_CHAOS=1 (dev-up.sh --chaos), the chaos override joins the stack: Toxiproxy in
# front of order-service's database, and fault-injection settings the system tests turn on.
compose() {
  local files=(-f "$COMPOSE_FILE")
  [[ "${COMMERCEFLOW_CHAOS:-0}" != 1 ]] || files+=(-f "$CHAOS_FILE")
  docker compose "${files[@]}" --env-file "$ENV_FILE" "$@"
}

require_docker() {
  command -v docker > /dev/null 2>&1 || die "docker is not installed or not on PATH"
  docker info > /dev/null 2>&1 || die "Docker is not running. Start Docker Desktop and try again."
  docker compose version > /dev/null 2>&1 || die "Docker Compose v2 is required (docker compose ...)"
}

# Reads KEY from .env without sourcing it (sourcing would execute anything in the file).
env_value() {
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1
}

# 48 hex characters from the OS random source.
random_secret() {
  od -An -tx1 -N24 /dev/urandom | tr -d ' \n'
}

port_in_use() {
  (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null
}

# Shared by dev-up.sh and kind-up.sh.
# .env: create it, or add keys that appeared in .env.example since it was created.
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

# JWT signing key: a local throwaway key, never committed.
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


# Service jars, built on the host and copied into images.
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

# Loads public images into the kind cluster. `kind load docker-image` fails on them with Docker
# Desktop's containerd image store: a multi-platform image is kept for this machine's platform
# only, and kind imports every platform. Saving just the node's platform to an archive works; a
# plain load is tried first, which is enough with Docker's classic image store (CI runners).
kind_load_public() {
  local cluster="$1" archive image platform
  shift
  for image in "$@"; do
    docker image inspect "$image" > /dev/null 2>&1 || docker pull --quiet "$image" > /dev/null
  done
  kind load docker-image --name "$cluster" "$@" > /dev/null 2>&1 && return 0
  platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
  archive="$ROOT/deploy/k8s/.generated/public-images.tar"
  mkdir -p "$(dirname "$archive")"
  docker save --platform "$platform" -o "$archive" "$@"
  kind load image-archive --name "$cluster" "$archive" > /dev/null
  rm -f "$archive"
}
