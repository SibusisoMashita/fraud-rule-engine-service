#!/bin/sh
set -eu
PGPASSWORD=$(cat /run/secrets/db-admin-password)
FRAUD_POLICY_PASSWORD=$(cat /run/secrets/db-policy-password)
FRAUD_SECURITY_PASSWORD=$(cat /run/secrets/db-security-password)
FRAUD_POLICY_API_PASSWORD=
if [ -f /run/secrets/fraud.policy-management.password ]; then
    FRAUD_POLICY_API_PASSWORD=$(cat /run/secrets/fraud.policy-management.password)
    test -n "$FRAUD_POLICY_API_PASSWORD" || { echo 'Empty policy API database password' >&2; exit 1; }
fi
export PGPASSWORD FRAUD_POLICY_PASSWORD FRAUD_SECURITY_PASSWORD FRAUD_POLICY_API_PASSWORD
psql -v ON_ERROR_STOP=1 --host=db --username=postgres --dbname=fraud <<'SQL'
\getenv policy_password FRAUD_POLICY_PASSWORD
SELECT format('CREATE ROLE fraud_policy_admin LOGIN PASSWORD %L', :'policy_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_policy_admin')
\gexec
\getenv security_password FRAUD_SECURITY_PASSWORD
SELECT format('CREATE ROLE fraud_security_admin LOGIN PASSWORD %L', :'security_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_security_admin')
\gexec
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_policy_api') THEN
        CREATE ROLE fraud_policy_api NOLOGIN;
        COMMENT ON ROLE fraud_policy_api IS 'fraud-policy-api-unconfigured';
    END IF;
END;
$$;
\getenv policy_api_password FRAUD_POLICY_API_PASSWORD
SELECT format('ALTER ROLE fraud_policy_api LOGIN PASSWORD %L', :'policy_api_password')
WHERE :'policy_api_password' <> ''
  AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_policy_api' AND NOT rolcanlogin
              AND shobj_description(oid, 'pg_authid') = 'fraud-policy-api-unconfigured')
\gexec
SELECT 'COMMENT ON ROLE fraud_policy_api IS NULL'
WHERE :'policy_api_password' <> ''
  AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_policy_api' AND rolcanlogin
              AND shobj_description(oid, 'pg_authid') = 'fraud-policy-api-unconfigured')
\gexec
GRANT CONNECT ON DATABASE fraud TO fraud_policy_admin, fraud_security_admin, fraud_policy_api;
SQL
