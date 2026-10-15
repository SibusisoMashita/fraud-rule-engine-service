# Fraud Rule Engine Service

A Java backend for evaluating categorized transaction events, storing explainable fraud flags, and retrieving results through an API.

**Status:** runtime foundation. The application exposes liveness and dependency/schema readiness. Fraud evaluation, API credentials, quotas and business endpoints are planned; requests outside the two health routes are denied. The OpenAPI business contract and policy examples remain draft specifications.

## Scope

V1 accepts immutable **BOOKED outgoing card purchases and ATM withdrawals** from authenticated producers. It flags potential fraud; it does not authorize payments, establish fraud, calculate balances, or process refunds/reversals. Producers supply a stable event identity, opaque customer/instrument identifiers and trustworthy physical-location evidence when available. The demonstration dataset is synthetic.

Five configurable rules cover high amounts, transaction velocity, cumulative debit spend, impossible travel and small payments followed by a large payment.

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

Readiness may return 503 while the application starts. Only port 8080 is exposed, bound to loopback; PostgreSQL and Redis have no host ports. Use `APP_PORT=18080 docker compose up -d --no-build` if 8080 is occupied. Generated local credentials live in the ignored `secrets/` directory. The script preserves existing secrets. Compose initializes database identities on the first empty volume, runs the one-shot migration, then starts the application.

## Test

With `JAVA_HOME` pointing to JDK 21:

```sh
./mvnw -B -ntp verify
./scripts/smoke.sh
```

`verify` runs integration tests against disposable PostgreSQL and Redis containers and packages the executable JAR. The smoke test requires the locally built image, uses a separate disposable Compose project on port 18080, and removes only that project's containers and volumes. Set `SMOKE_PORT` when necessary. Docker must be available; integration tests are not silently skipped.

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

[Fraud policy](config/policy.example.yaml) and [service limits](config/service.example.yaml) are design examples, not application-bound settings or calibrated bank thresholds.
