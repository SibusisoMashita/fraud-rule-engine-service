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
flow_tmp=$(mktemp -d)
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
    rm -rf "$flow_tmp"
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
api_request() {
    api_origin=$1 api_method=$2 api_path=$3 api_input=$4 api_output=$5
    if [ "$api_method" = POST ]; then
        printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
            curl --config - -sS --max-time 15 -H 'Content-Type: application/json' --data-binary "@$api_input" -o "$api_output" -w '%{http_code}' "$api_origin$api_path"
    else
        printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
            curl --config - -sS --max-time 5 -o "$api_output" -w '%{http_code}' "$api_origin$api_path"
    fi
}
# Two application processes race on one event; exactly one creates it.
flow_time=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
printf '{"eventId":"smoke-race","customerId":"smoke-customer","instrumentId":"smoke-card","transactionType":"CARD_PURCHASE","status":"BOOKED","channel":"ECOMMERCE","amount":"25000.01","currency":"ZAR","category":"GROCERIES","occurredAt":"%s"}\n' "$flow_time" > "$flow_tmp/event.json"
api_request "$base" POST /api/v1/transactions "$flow_tmp/event.json" "$flow_tmp/first.json" > "$flow_tmp/first.status" &
flow_first_pid=$!
api_request "$peer_base" POST /api/v1/transactions "$flow_tmp/event.json" "$flow_tmp/second.json" > "$flow_tmp/second.status" &
flow_second_pid=$!
wait "$flow_first_pid"
wait "$flow_second_pid"
flow_statuses="$(cat "$flow_tmp/first.status") $(cat "$flow_tmp/second.status")"
case "$flow_statuses" in '201 200'|'200 201') ;; *) echo "Unexpected duplicate outcomes: $flow_statuses" >&2; exit 1;; esac
cmp -s "$flow_tmp/first.json" "$flow_tmp/second.json"
grep -Eq '"decision"[[:space:]]*:[[:space:]]*"FLAGGED"' "$flow_tmp/first.json"
test "$(api_request "$peer_base" GET /api/v1/transactions/smoke-race /dev/null "$flow_tmp/get.json")" = 200
cmp -s "$flow_tmp/first.json" "$flow_tmp/get.json"
sed 's/25000.01/25000.02/' "$flow_tmp/event.json" > "$flow_tmp/conflict.json"
test "$(api_request "$base" POST /api/v1/transactions "$flow_tmp/conflict.json" "$flow_tmp/conflict-response.json")" = 409
compose exec -T db psql -U postgres -d fraud -Atc "SELECT count(*) FROM fraud.transactions WHERE scope='local-demo'; SELECT count(*) FROM fraud.evaluations WHERE scope='local-demo'; SELECT count(*) FROM fraud.rule_results WHERE scope='local-demo'; SELECT customer_sequence FROM fraud.customer_processing_state WHERE scope='local-demo'" > "$flow_tmp/counts"
printf '1\n1\n5\n1\n' > "$flow_tmp/expected-counts"
cmp -s "$flow_tmp/counts" "$flow_tmp/expected-counts"
# Distinct events alternate between app processes but share one customer history.
history_n=1
while [ "$history_n" -le 6 ]; do
    history_origin=$base
    if [ $((history_n % 2)) -eq 0 ]; then history_origin=$peer_base; fi
    printf '{"eventId":"history-%s","customerId":"history-customer","instrumentId":"history-card","transactionType":"CARD_PURCHASE","status":"BOOKED","channel":"ECOMMERCE","amount":"6000.00","currency":"ZAR","category":"GROCERIES","occurredAt":"%s"}\n' "$history_n" "$flow_time" > "$flow_tmp/history-$history_n.json"
    test "$(api_request "$history_origin" POST /api/v1/transactions "$flow_tmp/history-$history_n.json" "$flow_tmp/history-$history_n-response.json")" = 201
    history_decision=CLEAR
    if [ "$history_n" -eq 6 ]; then history_decision=FLAGGED; fi
    grep -Eq "\"decision\"[[:space:]]*:[[:space:]]*\"$history_decision\"" "$flow_tmp/history-$history_n-response.json"
    history_n=$((history_n + 1))
done
compose exec -T db psql -U postgres -d fraud -Atc "SELECT evidence->>'count' FROM fraud.rule_results WHERE scope='local-demo' AND event_id='history-6' AND rule_id='TRANSACTION_VELOCITY'; SELECT evidence->>'total' FROM fraud.rule_results WHERE scope='local-demo' AND event_id='history-6' AND rule_id='CUMULATIVE_SPEND'" > "$flow_tmp/history-facts"
printf '6\n36000\n' > "$flow_tmp/expected-history-facts"
cmp -s "$flow_tmp/history-facts" "$flow_tmp/expected-history-facts"
history_started=$(docker inspect -f '{{.State.StartedAt}}' "$app_id")
history_peer_started=$(docker inspect -f '{{.State.StartedAt}}' "$peer_id")
sed -e 's/maxTransactions: 5/maxTransactions: 10/' -e 's/30000.00/50000.00/' config/policy.example.yaml > "$policy_fixture"
./scripts/activate-policy.sh "$policy_fixture"
test "$(docker inspect -f '{{.State.StartedAt}}' "$app_id")" = "$history_started"
test "$(docker inspect -f '{{.State.StartedAt}}' "$peer_id")" = "$history_peer_started"
test "$(api_request "$peer_base" POST /api/v1/transactions "$flow_tmp/history-6.json" "$flow_tmp/history-replay.json")" = 200
cmp -s "$flow_tmp/history-6-response.json" "$flow_tmp/history-replay.json"
sed -e 's/history-6/history-7/' -e 's/6000.00/1000.00/' "$flow_tmp/history-6.json" > "$flow_tmp/history-7.json"
test "$(api_request "$peer_base" POST /api/v1/transactions "$flow_tmp/history-7.json" "$flow_tmp/history-7-response.json")" = 201
grep -Eq '"decision"[[:space:]]*:[[:space:]]*"CLEAR"' "$flow_tmp/history-7-response.json"
compose exec -T db psql -U postgres -d fraud -Atc "SELECT evidence->>'total' FROM fraud.rule_results WHERE scope='local-demo' AND event_id='history-7' AND rule_id='CUMULATIVE_SPEND'" | grep -qx 37000
sed '/^  transactionVelocity:/,/^  impossibleTravel:/s/enabled: true/enabled: false/' config/policy.example.yaml > "$policy_fixture"
./scripts/activate-policy.sh "$policy_fixture"
sed 's/history-7/history-8/' "$flow_tmp/history-7.json" > "$flow_tmp/history-8.json"
test "$(api_request "$base" POST /api/v1/transactions "$flow_tmp/history-8.json" "$flow_tmp/history-8-response.json")" = 201
compose exec -T db psql -U postgres -d fraud -Atc "SELECT count(*) FROM fraud.rule_results WHERE scope='local-demo' AND event_id='history-8' AND status='DISABLED'" | grep -qx 4
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
test "$(api_request "$base" POST /api/v1/transactions "$flow_tmp/event.json" "$flow_tmp/replay.json")" = 200
cmp -s "$flow_tmp/first.json" "$flow_tmp/replay.json"
sed -e 's/smoke-race/live-policy-event/' -e 's/smoke-customer/policy-customer/' -e 's/smoke-card/policy-card/' "$flow_tmp/event.json" > "$flow_tmp/live.json"
test "$(api_request "$base" POST /api/v1/transactions "$flow_tmp/live.json" "$flow_tmp/live-response.json")" = 201
grep -Eq '"decision"[[:space:]]*:[[:space:]]*"CLEAR"' "$flow_tmp/live-response.json"
grep -Eq "\"policyHash\"[[:space:]]*:[[:space:]]*\"$active_hash\"" "$flow_tmp/live-response.json"
compose run --rm --no-deps policy-seed
test "$(head_hash)" = "$active_hash"
sed '/^  impossibleTravel:/,/^  smallPaymentBurstThenLarge:/s/enabled: false/enabled: true/' config/policy.example.yaml > "$policy_fixture"
if ./scripts/activate-policy.sh "$policy_fixture"; then
    echo 'Unavailable rule unexpectedly activated' >&2
    exit 1
fi
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
compose exec -T db psql -U postgres -d fraud -Atc 'SELECT version FROM fraud.service_metadata' | grep -qx 5
compose stop app
compose logs app | grep -q 'Graceful shutdown complete'
compose up -d --no-build
ready
# Re-running the real migration entrypoint must preserve the installed schema.
compose run --rm --no-deps migrate
compose exec -T db psql -U postgres -d fraud -Atc 'SELECT count(*) FROM fraud.flyway_schema_history WHERE success' | grep -qx 5
test "$(head_hash)" = "$active_hash"
test "$(api_request "$base" GET /api/v1/transactions/smoke-race /dev/null "$flow_tmp/restarted.json")" = 200
cmp -s "$flow_tmp/first.json" "$flow_tmp/restarted.json"
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
echo 'Runtime, live-policy, protected-ingress, transactional-evaluation and historical-rule smoke checks passed.'
