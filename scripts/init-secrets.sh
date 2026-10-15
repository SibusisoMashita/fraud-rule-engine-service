#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p secrets
chmod 700 secrets
for name in db-admin-password db-migration-password db-policy-password spring.datasource.password spring.data.redis.password; do
    if [ ! -e "secrets/$name" ]; then
        openssl rand -hex 32 > "secrets/$name"
    fi
    chmod 444 "secrets/$name"
    test -s "secrets/$name" || { echo "Empty secret: $name" >&2; exit 1; }
done
echo 'Local secrets are ready in the ignored secrets directory.'
