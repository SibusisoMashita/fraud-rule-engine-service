#!/bin/sh
set -eu
PGPASSWORD=$(cat /run/secrets/db-security-password)
key=$(cat /run/secrets/local-api-key)
case "$key" in local.*) secret=${key#local.};; *) echo 'Invalid local key format' >&2; exit 1;; esac
case "$secret" in *[!0-9a-f]*|'') echo 'Invalid local key secret' >&2; exit 1;; esac
test "${#secret}" -eq 64
FRAUD_LOCAL_KEY_HASH=$(printf '%s' "$secret" | sha256sum | cut -d ' ' -f 1)
export PGPASSWORD FRAUD_LOCAL_KEY_HASH
psql -v ON_ERROR_STOP=1 --host=db --username=fraud_security_admin --dbname=fraud <<'SQL'
\getenv key_hash FRAUD_LOCAL_KEY_HASH
\getenv validity_days LOCAL_KEY_VALIDITY_DAYS
BEGIN;
INSERT INTO fraud.api_principals(principal_id, scope, enabled, roles, location_sources)
VALUES ('local-demo', 'local-demo', true, ARRAY['INGEST','READ','POLICY'],
        ARRAY['VERIFIED_ATM_TERMINAL','VERIFIED_CARD_PRESENT_TERMINAL'])
ON CONFLICT (principal_id) DO NOTHING;
INSERT INTO fraud.api_credentials(key_id, principal_id, secret_hash, valid_from, expires_at)
VALUES ('local', 'local-demo', :'key_hash', clock_timestamp(),
        clock_timestamp() + make_interval(days => :'validity_days'::integer))
ON CONFLICT (key_id) DO NOTHING;
COMMIT;
SQL
