#!/bin/sh
set -eu
PGPASSWORD=$(cat /run/secrets/db-admin-password)
FRAUD_POLICY_PASSWORD=$(cat /run/secrets/db-policy-password)
FRAUD_SECURITY_PASSWORD=$(cat /run/secrets/db-security-password)
export PGPASSWORD FRAUD_POLICY_PASSWORD FRAUD_SECURITY_PASSWORD
psql -v ON_ERROR_STOP=1 --host=db --username=postgres --dbname=fraud <<'SQL'
\getenv policy_password FRAUD_POLICY_PASSWORD
SELECT format('CREATE ROLE fraud_policy_admin LOGIN PASSWORD %L', :'policy_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_policy_admin')
\gexec
\getenv security_password FRAUD_SECURITY_PASSWORD
SELECT format('CREATE ROLE fraud_security_admin LOGIN PASSWORD %L', :'security_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_security_admin')
\gexec
GRANT CONNECT ON DATABASE fraud TO fraud_policy_admin, fraud_security_admin;
SQL
