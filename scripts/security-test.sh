#!/usr/bin/env bash
# Runs the security suite (system-tests SecurityIT) against the running stack: forged and expired
# tokens, authorisation and cross-account checks, replayed idempotency keys and refresh tokens,
# invalid input, security headers, and scans of the services' logs and metrics.
#
#   scripts/dev-up.sh --apps      first: the stack, including the web app
#   scripts/security-test.sh      under a minute; docs/security/SECURITY.md maps tests to threats
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

require_docker
port_in_use 8080 || die "the gateway is not running on :8080; start the stack with scripts/dev-up.sh --apps"
port_in_use 8000 || die "the web app is not running on :8000; start the stack with scripts/dev-up.sh --apps"

(cd "$ROOT" && env -u MSYS_NO_PATHCONV -u MSYS2_ARG_CONV_EXCL ./mvnw -B -ntp -Psystem-tests \
  verify -pl system-tests -Dit.test=SecurityIT -Drun=security)
