#!/bin/sh
# Creates one empty database and one login per planned business service.
#
# Runs on every start (FR-007), so every step is conditional: create the role only if missing, create
# the database only if missing, and always re-apply the password so rotating it in .env takes effect.
# No tables are created: each service owns its own schema and will add Flyway migrations in its spec.
#
# Expects PGHOST/PGPORT (or defaults), POSTGRES_USER, PGPASSWORD and one <SERVICE>_DB_PASSWORD each.
set -eu

: "${POSTGRES_USER:?POSTGRES_USER is required}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"

SERVICES="auth course enrollment payment certificate review notification"

psql_admin() {
  psql --host="$PGHOST" --port="$PGPORT" --username="$POSTGRES_USER" --dbname=postgres \
       --no-password --set ON_ERROR_STOP=1 "$@"
}

# Wait briefly: on a cold start the container can accept connections a moment after it reports ready.
attempt=0
until psql_admin --quiet --command 'SELECT 1' >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "db-init: database at $PGHOST:$PGPORT did not accept connections after 30 attempts" >&2
    exit 1
  fi
  sleep 1
done

for service in $SERVICES; do
  # <SERVICE>_DB_PASSWORD, for example AUTH_DB_PASSWORD.
  upper="$(echo "$service" | tr '[:lower:]' '[:upper:]')"
  eval "password=\${${upper}_DB_PASSWORD:-}"
  if [ -z "$password" ]; then
    echo "db-init: ${upper}_DB_PASSWORD is required" >&2
    exit 1
  fi

  # Quote the password for SQL by doubling any single quotes.
  escaped="$(printf '%s' "$password" | sed "s/'/''/g")"

  # Role: create when absent, and always re-apply the password so a rotated secret takes effect.
  psql_admin --quiet --command "
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${service}') THEN
        CREATE ROLE ${service} LOGIN PASSWORD '${escaped}';
      END IF;
    END
    \$\$;"
  psql_admin --quiet --command "ALTER ROLE ${service} WITH LOGIN PASSWORD '${escaped}';"

  # Database: CREATE DATABASE cannot run inside a transaction block, so it cannot go in the DO block
  # above. Check first, then create.
  exists="$(psql_admin --tuples-only --no-align \
            --command "SELECT 1 FROM pg_database WHERE datname = '${service}'")"
  if [ -z "$exists" ]; then
    psql_admin --quiet --command "CREATE DATABASE ${service} OWNER ${service};"
  fi

  # Constitution Principle II: only the owning service may reach its database.
  psql_admin --quiet --command "REVOKE ALL ON DATABASE ${service} FROM PUBLIC;"
  psql_admin --quiet --command "GRANT ALL PRIVILEGES ON DATABASE ${service} TO ${service};"

  echo "db-init: ${service} ready"
done

echo "db-init: all ${0##*/} work complete"
