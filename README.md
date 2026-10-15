# Fraud Rule Engine Service

A Java backend for evaluating categorized transaction events, storing explainable fraud flags, and retrieving results through an API.

**Status:** the first transactional flow is implemented: authenticated `POST /api/v1/transactions` evaluates the high-amount rule and commits the event, evaluation and complete rule-result set together. Identical retries return the original result; `GET /api/v1/transactions/{eventId}` retrieves it within the caller's scope. Historical rules and list/policy-query endpoints remain planned.

## Scope

V1 accepts immutable **BOOKED outgoing card purchases and ATM withdrawals** from authenticated producers. It flags potential fraud; it does not authorize payments, establish fraud, calculate balances, or process refunds/reversals. Producers supply a stable event identity, opaque customer/instrument identifiers and trustworthy physical-location evidence when available. The demonstration dataset is synthetic.

The design defines five rules: high amount is implemented; velocity, cumulative spend, impossible travel and the small-payment sequence follow in later milestones. The sample policy enables only high amount. All thresholds, currencies, eligibility windows and enabled states come from the active database policy. Enabling an unavailable implementation is rejected.

## Stack

Java 21, Spring Boot 4.1.1 (MVC/Security/JDBC), HikariCP, PostgreSQL 17, Flyway, Redis 8.2, Maven 3.9.14 Wrapper and JUnit/Testcontainers. Maven versions are managed in `pom.xml`; Docker base/service images are pinned by digest. Redis enforces shared principal/route quotas through an atomic token bucket.

## Build and run

Prerequisites: Docker Engine with Docker Compose v2.24 or later, OpenSSL, curl and a POSIX shell. Local Maven tests additionally require a JDK 21 installation and access to the Docker engine. Image and dependency downloads require internet access.

```sh
git clone https://github.com/SibusisoMashita/fraud-rule-engine-service.git
cd fraud-rule-engine-service
./scripts/init-secrets.sh
docker compose build app
docker compose up -d --no-build --wait --wait-timeout 120
curl --fail http://localhost:8080/health/live
curl --fail http://localhost:8080/health/ready
```

Readiness may return 503 while the application starts. Only port 8080 is exposed, bound to loopback; PostgreSQL and Redis have no host ports. Use `APP_PORT=18080 docker compose up -d --no-build` if 8080 is occupied. Generated local credentials live in the ignored `secrets/` directory. The script preserves existing secrets. Compose initializes database identities, provisions the local configuration roles, runs migrations and seeds policy and a local demo identity only when absent. It then starts the application. Existing policy and identity changes, including revocation, are preserved on subsequent starts.

## Business configuration

Fraud thresholds, windows, currencies, evidence eligibility and enabled states live in versioned PostgreSQL policy. To validate and activate a policy file against a running stack:

```sh
./scripts/activate-policy.sh config/policy.example.yaml
```

Use a working copy outside Git for business changes. Activation takes effect at the next policy selection without rebuilding or restarting the app. A selection already in progress retains its whole immutable version. Editing the seed file alone does not change active policy. [Operations](docs/OPERATIONS.md) describes permissions, rollback and failure handling.

[Service limits](config/service.example.yaml) are strict startup configuration for infrastructure; changes require a coordinated restart. Protocol/storage bounds and mathematical constants are code contracts, rather than adjustable fraud thresholds.

## Protected ingress

The ignored `secrets/local-api-key` contains a generated local demo credential. Supply it in the `X-API-Key` header; this example passes it through standard input rather than a process argument:

```sh
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include http://localhost:8080/api/v1/evaluations
```

The seeded identity has the ingest/read/policy roles in the `local-demo` scope, with a default 30-day validity configured by `LOCAL_KEY_VALIDITY_DAYS` on initial seeding. List-evaluation and rule-inspection routes currently return `503 FEATURE_UNAVAILABLE`; missing/invalid credentials return 401, disallowed roles/sources return 403 and exhausted quotas return 429. [Operations](docs/OPERATIONS.md) covers identity changes, rotation and the implemented bounds.

## Evaluate and retrieve

This synthetic example saves its immutable payload for replay. With the initial sample policy, its amount exceeds the configured threshold; a business policy change can change the decision for a new event.

```sh
if [ ! -f secrets/example-transaction.json ]; then
  event_time=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  printf '{"eventId":"demo-event-001","customerId":"demo-customer","instrumentId":"demo-card","transactionType":"CARD_PURCHASE","status":"BOOKED","channel":"ECOMMERCE","amount":"25000.01","currency":"ZAR","category":"GROCERIES","occurredAt":"%s"}\n' "$event_time" > secrets/example-transaction.json
fi
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include -H 'Content-Type: application/json' \
    --data-binary @secrets/example-transaction.json http://localhost:8080/api/v1/transactions
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include http://localhost:8080/api/v1/transactions/demo-event-001
```

A new committed event returns 201 and `Location`; an identical POST returns 200 with the same stored evaluation. Reuse the saved file unchanged to replay it. Changed content under the same scoped event ID returns 409; a new payment needs a new event ID. Disabled rules appear explicitly as `DISABLED`. After a timeout or uncertain 5xx response, retrieve or replay the same ID/payload instead of generating a replacement identity.

Existing Stage 3 volumes need a compatible active policy before the new app starts. [Operations](docs/OPERATIONS.md) describes that upgrade; seeding never silently overwrites business settings.

## Test

With `JAVA_HOME` pointing to JDK 21:

```sh
./mvnw -B -ntp verify
./scripts/smoke.sh
```

`verify` runs unit/contract tests and integration tests against disposable PostgreSQL and Redis containers and packages the executable JAR. The smoke test requires the locally built image and uses a separate disposable Compose project on port 18080. It covers two-process duplicate races, replay/conflict, live threshold changes, persisted retrieval and failure gates, then removes its containers, volumes and temporary fixtures. The two-instance check also uses port 18081. Set `SMOKE_PORT` and `SMOKE_PEER_PORT` when necessary. Docker must be available; integration tests are not silently skipped.

For Docker contexts using a non-default Unix socket, expose that context to Testcontainers before running Maven:

```sh
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Documentation

| Read this | For |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Boundaries, data correctness, security, scaling and failure trade-offs |
| [Rules](docs/RULES.md) | Exact predicates, outcomes, configuration and rule extension |
| [OpenAPI](api/openapi.yaml) | Draft HTTP contract, schemas, errors and endpoint permissions |
| [Testing](docs/TESTING.md) | Acceptance criteria, risk scenarios and required release evidence |
| [Operations](docs/OPERATIONS.md) | Local lifecycle, configuration and failure diagnosis |
| [Delivery plan](docs/DELIVERY_PLAN.md) | Implementation milestones and acceptance evidence |

[Fraud policy](config/policy.example.yaml) is the initial seed/activation example; values are illustrative, not calibrated bank thresholds. Configuration syntax, canonical identity and remaining implementation boundaries are documented in [Architecture](docs/ARCHITECTURE.md).
