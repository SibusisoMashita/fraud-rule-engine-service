#!/bin/sh
# Destructive operations are limited to this invocation's disposable Compose project.
set -eu
cd "$(dirname "$0")/.."
compose() {
    if [ -n "${COMPOSE_BIN:-}" ]; then "$COMPOSE_BIN" "$@"; else docker compose "$@"; fi
}
export COMPOSE_PROJECT_NAME="fraud-smoke-$$"
export APP_PORT="${SMOKE_PORT:-18080}"
base="http://127.0.0.1:$APP_PORT"
override=$(mktemp)
cleanup() {
    result=$?
    trap - EXIT
    if [ "$result" -ne 0 ]; then compose logs --tail=80; fi
    compose down --volumes --remove-orphans
    rm -f "$override"
    exit "$result"
}
trap cleanup EXIT
./scripts/init-secrets.sh
compose up -d --no-build
expect() {
    path=$1 expected=$2
    code=$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$base$path")
    test "$code" = "$expected" || { echo "$path returned $code; expected $expected" >&2; return 1; }
}
ready() {
    attempt=0
    until curl -fsS --max-time 5 "$base/health/ready" >/dev/null 2>&1; do
        attempt=$((attempt + 1))
        test "$attempt" -lt 90 || { echo 'Readiness timed out' >&2; return 1; }
        sleep 1
    done
}
ready
expect /health/live 200
app_id=$(compose ps -q app)
test "$(docker inspect -f '{{.Config.User}}' "$app_id")" = '10001:10001'
test "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$app_id")" = true
test "$(docker inspect -f '{{.HostConfig.Memory}}' "$app_id")" = 536870912
test "$(docker inspect -f '{{.HostConfig.PidsLimit}}' "$app_id")" = 128
test "$(docker inspect -f '{{.HostConfig.NanoCpus}}' "$app_id")" = 1000000000
test "$(docker inspect -f '{{json .HostConfig.CapDrop}}' "$app_id")" = '["ALL"]'
test -z "$(docker port "$(compose ps -q db)")"
test -z "$(docker port "$(compose ps -q redis)")"
compose exec -T redis redis-cli ping | grep -q NOAUTH
compose exec -T redis sh -ec 'REDISCLI_AUTH="$(cat /run/secrets/spring.data.redis.password)" redis-cli SET foundation:forbidden value' | grep -q NOPERM
expect /actuator/env 403
expect /api/v1/evaluations 403
compose stop redis
expect /health/ready 503
expect /health/live 200
compose start redis
ready
compose stop db
expect /health/ready 503
expect /health/live 200
compose start db
ready
compose exec -T db psql -U postgres -d fraud -Atc 'SELECT version FROM fraud.service_metadata' | grep -qx 1
compose stop app
compose logs app | grep -q 'Graceful shutdown complete'
compose up -d --no-build
ready
# Re-running the real migration entrypoint must preserve the installed schema.
compose run --rm migrate
compose exec -T db psql -U postgres -d fraud -Atc 'SELECT count(*) FROM fraud.flyway_schema_history WHERE success' | grep -qx 1
# A failed one-shot migration must prevent a fresh application from starting.
compose stop app
compose rm -f app migrate
cat > "$override" <<'YAML'
services:
  migrate:
    environment:
      SPRING_FLYWAY_LOCATIONS: filesystem:/missing
      SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS: 'true'
YAML
if compose -f compose.yaml -f "$override" up -d --no-build app; then
    echo 'Invalid migration unexpectedly succeeded' >&2
    exit 1
fi
test -z "$(compose ps --status running -q app)"
echo 'Foundation smoke checks passed.'
