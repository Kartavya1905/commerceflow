#!/usr/bin/env bash
# Runs the web app's end-to-end smoke test (frontend/e2e) against the running stack.
#
#   scripts/dev-up.sh --apps      first: the stack, including the web app on localhost:8000
#   scripts/e2e.sh                then: register, buy, see CONFIRMED; admin pages refused
#
# Runs in the Playwright image on the host network, so the browser reaches the app as
# localhost (the only plain-HTTP origin where it keeps the Secure refresh cookie), with its own
# node_modules (glibc builds) in a throwaway volume. Nothing is installed on this machine. The
# report lands in frontend/playwright-report.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PLAYWRIGHT_IMAGE=mcr.microsoft.com/playwright:v1.64.1-noble
require_docker
port_in_use 8000 || die "the web app is not running on :8000; start it with scripts/dev-up.sh --apps"

email=$(env_value BOOTSTRAP_ADMIN_EMAIL)
password=$(env_value BOOTSTRAP_ADMIN_PASSWORD)
[[ -n "$email" && -n "$password" ]] || die "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must be set in .env"

# Credentials go in through the environment, never on the command line.
E2E_ADMIN_EMAIL="$email" E2E_ADMIN_PASSWORD="$password" docker run --rm --network host --ipc host \
  -e E2E_ADMIN_EMAIL -e E2E_ADMIN_PASSWORD -e CI \
  -v "$ROOT/frontend:/work" -v /work/node_modules -w /work \
  "$PLAYWRIGHT_IMAGE" sh -c 'npm ci --no-audit --no-fund --loglevel=error && npx playwright test'
