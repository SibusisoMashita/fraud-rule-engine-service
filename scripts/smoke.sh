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
peer_id=
override=$(mktemp)
response_fixture=$(mktemp)
policy_fixture="secrets/$COMPOSE_PROJECT_NAME-policy.yaml"
limits_fixture="secrets/$COMPOSE_PROJECT_NAME-limits.yaml"
cleanup() {
    result=$?
    trap - EXIT
    if [ "$result" -ne 0 ]; then compose logs --tail=80 || true; fi
    if [ -n "$peer_id" ]; then docker rm -f "$peer_id" >/dev/null || true; fi
    compose down --volumes --remove-orphans || result=1
    rm -f "$override" "$response_fixture" "$policy_fixture" "$limits_fixture"
    exit "$result"
}
trap cleanup EXIT
./scripts/init-secrets.sh
compose up -d --no-build --wait --wait-timeout 120
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
expect /api/v1/evaluations 401
# The same deployed guards apply to both application instances.
auth_expect() {
    endpoint=$1 expected=$2
    code=$(printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
        curl --config - -sS --max-time 5 -o "$response_fixture" -w '%{http_code}' "$endpoint")
    test "$code" = "$expected" || { echo "Protected endpoint returned $code; expected $expected" >&2; return 1; }
    case "$expected" in
        401) expected_code=INVALID_CREDENTIAL;;
        429) expected_code=RATE_LIMITED;;
        503) expected_code=FEATURE_UNAVAILABLE;;
        *) echo 'Unsupported protected smoke expectation' >&2; return 1;;
    esac
    grep -Eq "\"code\"[[:space:]]*:[[:space:]]*\"$expected_code\"" "$response_fixture"
    grep -Eq '"outcome"[[:space:]]*:[[:space:]]*"NOT_ACCEPTED"' "$response_fixture"
}
auth_expect "$base/api/v1/evaluations" 503
compose exec -T redis sh -ec 'REDISCLI_AUTH="$(cat /run/secrets/spring.data.redis.password)" redis-cli HSET outside:key tokens 1' | grep -q NOPERM
peer_port=${SMOKE_PEER_PORT:-18081}
peer_id=$(compose run -d --no-deps -p "127.0.0.1:$peer_port:8080" app)
peer_base="http://127.0.0.1:$peer_port"
attempt=0
until curl -fsS --max-time 5 "$peer_base/health/ready" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    test "$attempt" -lt 90 || { echo 'Peer readiness timed out' >&2; exit 1; }
    sleep 1
done
auth_expect "$peer_base/api/v1/evaluations" 503
principal_hash=$(printf local-demo | openssl dgst -sha256 | awk '{print $NF}')
quota_key="fraud:quota:$principal_hash:READ"
quota_time=$(( $(date +%s) * 1000 + 60000 ))
compose exec -T redis sh -ec 'REDISCLI_AUTH="$(cat /run/secrets/spring.data.redis.password)" redis-cli HSET "$1" tokens 0 time "$2"' sh "$quota_key" "$quota_time" >/dev/null
auth_expect "$base/api/v1/evaluations" 429
auth_expect "$peer_base/api/v1/evaluations" 429
docker rm -f "$peer_id" >/dev/null
peer_id=
# Local seeding cannot undo a security administrator's revocation.
compose exec -T db psql -U postgres -d fraud -c "UPDATE fraud.api_principals SET enabled=false WHERE principal_id='local-demo'" >/dev/null
auth_expect "$base/api/v1/evaluations" 401
compose run --rm --no-deps identity-seed
auth_expect "$base/api/v1/evaluations" 401
compose exec -T db psql -U postgres -d fraud -c "UPDATE fraud.api_principals SET enabled=true WHERE principal_id='local-demo'" >/dev/null
# Business activation must not restart the running app or reset on the next seed/startup.
head_hash() { compose exec -T db psql -U postgres -d fraud -Atc 'SELECT policy_hash FROM fraud.policy_head WHERE singleton'; }
initial_hash=$(head_hash)
started_at=$(docker inspect -f '{{.State.StartedAt}}' "$app_id")
sed 's/25000.00/25000.01/' config/policy.example.yaml > "$policy_fixture"
./scripts/activate-policy.sh "$policy_fixture"
active_hash=$(head_hash)
test "$initial_hash" != "$active_hash"
ready
test "$(docker inspect -f '{{.State.StartedAt}}' "$app_id")" = "$started_at"
compose run --rm --no-deps policy-seed
test "$(head_hash)" = "$active_hash"
sed 's/enabled: true/enabled: false/g' config/policy.example.yaml > "$policy_fixture"
if ./scripts/activate-policy.sh "$policy_fixture"; then
    echo 'Invalid policy unexpectedly activated' >&2
    exit 1
fi
test "$(head_hash)" = "$active_hash"
ready
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
compose exec -T db psql -U postgres -d fraud -Atc 'SELECT version FROM fraud.service_metadata' | grep -qx 3
compose stop app
compose logs app | grep -q 'Graceful shutdown complete'
compose up -d --no-build
ready
# Re-running the real migration entrypoint must preserve the installed schema.
compose run --rm --no-deps migrate
compose exec -T db psql -U postgres -d fraud -Atc 'SELECT count(*) FROM fraud.flyway_schema_history WHERE success' | grep -qx 3
test "$(head_hash)" = "$active_hash"
# Invalid operational settings must block a new application process.
compose stop app
compose rm -f app
sed 's/poolAcquireTimeoutMillis: 500/poolAcquireTimeoutMillis: 499/' config/service.example.yaml > "$limits_fixture"
cat > "$override" <<YAML
services:
  app:
    volumes:
      - ./$limits_fixture:/app/config/service.example.yaml:ro
YAML
if compose -f compose.yaml -f "$override" up -d --no-build --wait --wait-timeout 30 app; then
    echo 'Invalid operational settings unexpectedly started' >&2
    exit 1
fi
test -z "$(compose ps --status running -q app)"
compose logs app | grep -q 'Pool acquisition'
compose rm -f app
compose up -d --no-build --wait --wait-timeout 120
ready
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
echo 'Runtime, live-policy and protected-ingress smoke checks passed.'
