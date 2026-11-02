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

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
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
