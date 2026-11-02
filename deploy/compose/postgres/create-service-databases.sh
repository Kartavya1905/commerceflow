#!/bin/sh
# Creates (or reconciles) one database and one login role per service.
#
# Safe to run on every start: roles and databases are created only if missing, and passwords
# and privileges are re-applied from the environment each time. Each role owns only its own
# database, and CONNECT is revoked from PUBLIC, so a service cannot open another service's
# database even with a valid password (ADR-0006).
set -eu

export PGUSER="$POSTGRES_SUPERUSER"
export PGPASSWORD="$POSTGRES_SUPERUSER_PASSWORD"
export PGDATABASE=postgres

SERVICES="identity catalog order inventory payment notification sandbox"

fail() {
  echo "postgres-init: $*" >&2
  exit 1
}

for svc in $SERVICES; do
  var="$(echo "$svc" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
  eval "password=\${$var:-}"
  [ -n "$password" ] || fail "$var is empty"
  case "$password" in CHANGE_ME*) fail "$var still has its placeholder value; edit .env" ;; esac
  [ "${#password}" -ge 16 ] || fail "$var must be at least 16 characters"

  role="${svc}_svc"
  db="${svc}_db"

  # psql substitutes :'name' as a quoted literal and :"name" as a quoted identifier, so the
  # values are never spliced into SQL as raw text.
  psql -v ON_ERROR_STOP=1 -q -v role="$role" -v db="$db" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec

ALTER ROLE :"role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  CONNECTION LIMIT 60 PASSWORD :'password';

SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec

ALTER DATABASE :"db" OWNER TO :"role";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
  echo "postgres-init: $db ready for $role"
done

# Service roles have no business in the maintenance databases either.
psql -v ON_ERROR_STOP=1 -q <<'SQL'
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
SQL
echo "postgres-init: done"
