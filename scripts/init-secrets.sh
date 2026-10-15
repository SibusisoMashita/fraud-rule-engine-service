#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p secrets
chmod 700 secrets
for name in db-admin-password db-migration-password db-policy-password db-security-password spring.datasource.password spring.data.redis.password; do
    if [ ! -e "secrets/$name" ]; then
        openssl rand -hex 32 > "secrets/$name"
    fi
    chmod 444 "secrets/$name"
    test -s "secrets/$name" || { echo "Empty secret: $name" >&2; exit 1; }
done
if [ ! -e secrets/local-api-key ]; then
    printf 'local.%s\n' "$(openssl rand -hex 32)" > secrets/local-api-key
fi
chmod 444 secrets/local-api-key
test -s secrets/local-api-key || { echo 'Empty local API key' >&2; exit 1; }
echo 'Local secrets are ready in the ignored secrets directory.'
