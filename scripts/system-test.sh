#!/usr/bin/env bash
# Runs the failure-recovery system tests (system-tests/) against the running stack, N times in a
# row. Each run kills, stops, cuts and floods parts of the stack for real, then checks the
# business invariants; numbers go to system-tests/target/failure-recovery/results.jsonl.
#
#   scripts/dev-up.sh --apps --chaos     first: the stack, with Toxiproxy and fault injection
#   scripts/system-test.sh [runs]        then: runs defaults to 1; the acceptance bar is 5
#
# A run takes about 15 minutes. Do not use the stack for anything else meanwhile.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

runs="${1:-1}"
[[ "$runs" =~ ^[1-9][0-9]*$ ]] || die "runs must be a positive number"
require_docker
port_in_use 8474 || die "Toxiproxy is not running: start the stack with scripts/dev-up.sh --apps --chaos"

passed=0
for run in $(seq 1 "$runs"); do
  info "system tests, run $run of $runs"
  if (cd "$ROOT" && env -u MSYS_NO_PATHCONV -u MSYS2_ARG_CONV_EXCL ./mvnw -B -ntp -Psystem-tests \
    verify -pl system-tests -Dit.test=FailureRecoveryIT -Drun="$run"); then
    passed=$((passed + 1))
  else
    echo "run $run FAILED" >&2
  fi
done

info "$passed of $runs runs passed; results in system-tests/target/failure-recovery/results.jsonl"
[[ "$passed" -eq "$runs" ]]
