# Fraud Rule Engine Service

A Java backend for evaluating categorized transaction events, storing explainable fraud flags, and retrieving results through an API.

**Status:** typed configuration and domain contracts are implemented, including database-backed policy activation without application restarts. Health endpoints are available. Fraud evaluation, producer authentication, quotas and business HTTP endpoints remain planned; other routes are denied. OpenAPI remains the draft business HTTP contract.

## Scope

V1 accepts immutable **BOOKED outgoing card purchases and ATM withdrawals** from authenticated producers. It flags potential fraud; it does not authorize payments, establish fraud, calculate balances, or process refunds/reversals. Producers supply a stable event identity, opaque customer/instrument identifiers and trustworthy physical-location evidence when available. The demonstration dataset is synthetic.

The policy defines five rules covering high amounts, transaction velocity, cumulative debit spend, impossible travel and small payments followed by a large payment.

## Stack

Java 21, Spring Boot 4.1.1 (MVC/Security/JDBC), HikariCP, PostgreSQL 17, Flyway, Redis 8.2, Maven 3.9.14 Wrapper and JUnit/Testcontainers. Maven versions are managed in `pom.xml`; Docker base/service images are pinned by digest. Redis is connected for readiness; shared quotas arrive with protected ingress.

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

Readiness may return 503 while the application starts. Only port 8080 is exposed, bound to loopback; PostgreSQL and Redis have no host ports. Use `APP_PORT=18080 docker compose up -d --no-build` if 8080 is occupied. Generated local credentials live in the ignored `secrets/` directory. The script preserves existing secrets. Compose initializes database identities, provisions the local configuration role, runs migrations and seeds policy only when no active version exists. It then starts the application. Existing active policy is preserved on subsequent starts.

## Business configuration

Fraud thresholds, windows, currencies, evidence eligibility and enabled states live in versioned PostgreSQL policy. To validate and activate a policy file against a running stack:

```sh
./scripts/activate-policy.sh config/policy.example.yaml
```

Use a working copy outside Git for business changes. Activation takes effect at the next policy selection without rebuilding or restarting the app. A selection already in progress retains its whole immutable version. Editing the seed file alone does not change active policy. [Operations](docs/OPERATIONS.md) describes permissions, rollback and failure handling.

[Service limits](config/service.example.yaml) are strict startup configuration for infrastructure; changes require a coordinated restart. Protocol/storage bounds and mathematical constants are code contracts, rather than adjustable fraud thresholds.

## Test

With `JAVA_HOME` pointing to JDK 21:

```sh
./mvnw -B -ntp verify
./scripts/smoke.sh
```

`verify` runs unit/contract tests and integration tests against disposable PostgreSQL and Redis containers and packages the executable JAR. The smoke test requires the locally built image and uses a separate disposable Compose project on port 18080. It covers live policy activation, restart persistence and failure gates, then removes its containers, volumes and temporary fixtures. Set `SMOKE_PORT` when necessary. Docker must be available; integration tests are not silently skipped.

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
