#!/usr/bin/env bash
# Lists and replays dead-lettered messages through the gateway's admin API.
#
#   scripts/replay-dlt.sh list <dlt-topic> [limit]
#   scripts/replay-dlt.sh replay <dlt-topic> <partition> <offset>
#   scripts/replay-dlt.sh replay-all <dlt-topic>
#
# Example: scripts/replay-dlt.sh list payment.commands.v1.dlt
#
# A replay puts the record back on its original topic unchanged, so it goes through the normal
# consumer and its dedupe: replaying something twice, or something already applied, is harmless.
# Fix what made it fail first (docs/runbooks/infra.md#dead-letter-messages), or it parks again.
# Signs in as the bootstrap admin from .env; set GATEWAY_URL to use another gateway.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

gw="${GATEWAY_URL:-http://localhost:8080}"

usage() {
  sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
}

[[ $# -ge 2 ]] || usage
command=$1
topic=$2
[[ "$topic" =~ ^[a-z]+\.[a-z]+\.v[0-9]+\.dlt$ ]] || die "not a dead-letter topic: $topic"

admin_token() {
  local email password
  email=$(env_value BOOTSTRAP_ADMIN_EMAIL)
  password=$(env_value BOOTSTRAP_ADMIN_PASSWORD)
  [[ -n "$email" && -n "$password" ]] || die "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must be set in .env"
  curl -fsS --max-time 10 -H 'Content-Type: application/json' -H 'X-Token-Delivery: body' \
    -d "{\"email\":\"$email\",\"password\":\"$password\"}" "$gw/api/v1/auth/login" |
    sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

token=$(admin_token) || die "admin login failed at $gw"
[[ -n "$token" ]] || die "admin login returned no token"

list() {
  curl -fsS --max-time 30 -H "Authorization: Bearer $token" "$gw/api/v1/admin/dlt/$topic?limit=${1:-50}"
}

replay() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -d "{\"partition\":$1,\"offset\":$2}" \
    "$gw/api/v1/admin/dlt/$topic/replay")
  if [[ "$code" == 202 ]]; then
    info "replayed $topic partition $1 offset $2"
  else
    die "replay of $topic partition $1 offset $2 answered $code"
  fi
}

case "$command" in
  list)
    list "${3:-50}"
    echo
    ;;
  replay)
    [[ $# -eq 4 && "$3" =~ ^[0-9]+$ && "$4" =~ ^[0-9]+$ ]] || usage
    replay "$3" "$4"
    ;;
  replay-all)
    positions=$(list 500 | grep -oE '"partition":[0-9]+,"offset":[0-9]+' |
      sed -E 's/"partition":([0-9]+),"offset":([0-9]+)/\1 \2/')
    if [[ -z "$positions" ]]; then
      info "$topic is empty"
      exit 0
    fi
    while read -r partition offset; do
      replay "$partition" "$offset"
    done <<< "$positions"
    ;;
  *)
    usage
    ;;
esac
