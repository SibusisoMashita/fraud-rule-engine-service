#!/bin/sh
set -eu
FRAUD_MIGRATION_PASSWORD=$(cat /run/secrets/db-migration-password)
FRAUD_RUNTIME_PASSWORD=$(cat /run/secrets/spring.datasource.password)
export FRAUD_MIGRATION_PASSWORD FRAUD_RUNTIME_PASSWORD
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv migration_password FRAUD_MIGRATION_PASSWORD
\getenv runtime_password FRAUD_RUNTIME_PASSWORD
CREATE ROLE fraud_migrator LOGIN PASSWORD :'migration_password';
CREATE ROLE fraud_runtime LOGIN PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE fraud FROM PUBLIC;
GRANT CONNECT ON DATABASE fraud TO fraud_migrator, fraud_runtime;
CREATE SCHEMA fraud AUTHORIZATION fraud_migrator;
GRANT USAGE ON SCHEMA fraud TO fraud_runtime;
SQL
# Only a cluster initialized for this image family may be reopened by its guard.
if [ -n "${FRAUD_DATABASE_FAMILY:-}" ]; then
    printf '%s\n' "$FRAUD_DATABASE_FAMILY" > "$PGDATA/.fraud-runtime-family"
fi
