#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/init-secrets.sh
umask 077
if [ ! -e secrets/fraud.policy-management.password ]; then
    openssl rand -hex 32 > secrets/fraud.policy-management.password
fi
if [ ! -e secrets/local-policy-admin-api-key ]; then
    printf 'policy-admin.%s\n' "$(openssl rand -hex 32)" > secrets/local-policy-admin-api-key
fi
for name in fraud.policy-management.password local-policy-admin-api-key; do
    chmod 444 "secrets/$name"
    test -s "secrets/$name" || { echo "Empty secret: $name" >&2; exit 1; }
done
echo 'Optional policy-administration secrets are ready in the ignored secrets directory.'
