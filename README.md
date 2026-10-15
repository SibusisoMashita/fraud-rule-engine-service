# Fraud Rule Engine Service

A Java backend for evaluating categorized transaction events, storing explainable fraud flags, and retrieving results through an API.

**Status:** design stage. API, rule and configuration specifications are available; application implementation and build/run/test commands are not yet available.

## Scope

V1 accepts immutable **BOOKED outgoing card purchases and ATM withdrawals** from authenticated producers. It flags potential fraud; it does not authorize payments, establish fraud, calculate balances, or process refunds/reversals. Producers supply a stable event identity, opaque customer/instrument identifiers and trustworthy physical-location evidence when available. The demonstration dataset is synthetic.

Five configurable rules cover high amounts, transaction velocity, cumulative debit spend, impossible travel and small payments followed by a large payment.

## Planned stack

Java 21; Spring Boot/MVC/Security; Spring JDBC/JdbcClient and HikariCP; PostgreSQL and Flyway; Redis for shared API quotas; Maven Wrapper; JUnit/Testcontainers; Docker Compose. Exact dependency versions and image digests are pending runtime bootstrap.

## Documentation

| Read this | For |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Boundaries, data correctness, security, scaling and failure trade-offs |
| [Rules](docs/RULES.md) | Exact predicates, outcomes, configuration and rule extension |
| [OpenAPI](api/openapi.yaml) | Draft HTTP contract, schemas, errors and endpoint permissions |
| [Testing](docs/TESTING.md) | Acceptance criteria, risk scenarios and required release evidence |
| [Delivery plan](docs/DELIVERY_PLAN.md) | Implementation milestones and acceptance evidence |

[Fraud policy](config/policy.example.yaml) and [service limits](config/service.example.yaml) are design examples, not application-bound settings or calibrated bank thresholds.
