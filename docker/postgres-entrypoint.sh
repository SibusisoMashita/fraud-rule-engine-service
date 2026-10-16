#!/bin/sh
set -eu
: "${PGDATA:?PGDATA is required}" "${FRAUD_DATABASE_FAMILY:?Image family is required}"
if [ -d "$PGDATA" ] && [ -n "$(ls -A "$PGDATA")" ]; then
    if [ ! -s "$PGDATA/PG_VERSION" ] || [ ! -r "$PGDATA/.fraud-runtime-family" ] || [ "$(cat "$PGDATA/.fraud-runtime-family")" != "$FRAUD_DATABASE_FAMILY" ]; then
        echo 'Unrecognized PostgreSQL data directory: use a logical dump/restore into a fresh volume; see docs/OPERATIONS.md.' >&2
        exit 1
    fi
fi
exec /usr/local/bin/docker-entrypoint.sh "$@"
