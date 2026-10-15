# Delivery plan

Stages 0–4 are implemented. The first flow commits a scoped event, its high-amount evaluation, every configured rule result and customer state together. Database policy drives thresholds and enabled states; unsupported implementations cannot be activated. POST replay/conflict and scoped event GET are available. The next stage adds velocity and cumulative-spend history.

## Milestones

| Stage | Deliverable | Acceptance evidence |
| --- | --- | --- |
| 0. Design foundation | Service scope, architecture, rule specifications, draft API and configuration | Consistent contracts, valid document links and schema examples |
| 1. Runtime foundation | Java/Maven application, pinned dependencies, Docker/Compose, PostgreSQL/Redis, Flyway, CI and operational runbook | Clean startup, migrations, health checks, graceful shutdown and reproducible setup |
| 2. Configuration and contracts | Typed startup limits, live versioned database policy, canonical hashes, decimal/time models and outcome algebra | Invalid-setting rejection, atomic live activation/rollback, stable hashes, boundary fixtures and contract alignment |
| 3. Protected ingress | Scoped authentication/authorization, trusted-source permissions, shared quotas and bounded request processing | Access-isolation, validation, quota and dependency-failure tests |
| 4. First evaluation flow | Atomic persistence, idempotency, customer locking, high-amount rule and event retrieval | POST-to-GET flow, replay/conflict handling, rollback, duplicate races and lost-response recovery |
| 5. Historical rules | Velocity and cumulative debit spend | Window/currency boundaries, multi-instance history correctness and query measurements |
| 6. Impossible travel | Trusted physical evidence, distance/time/uncertainty and bounded candidates | Plausible flights, equal-time/delayed events, different instruments and missing-reference cases |
| 7. Payment sequence | Small-payment burst followed by a large payment | Event-time ordering, decision-table coverage, policy-change replay and rule-extension tests |
| 8. Evaluation queries | Authorized filtered keyset pages and active-policy inspection | Runtime contract tests, access isolation, bounded queries and live-pagination behaviour |
| 9. Operational verification | Recovery, deployment compatibility, resource limits and measured capacity | Fault/kill/migration/restore drills, two-instance tests, benchmark reports and security scans |
| 10. Release validation | Complete usage documentation, examples and limitations | Clean-clone build/run/test demonstration and the release criteria in [Testing](TESTING.md) |

Stages build on the preceding contracts and runtime capabilities. Related implementation, configuration, tests and documentation form one reviewable change. The [testing plan](TESTING.md) defines the detailed acceptance scenarios; the table above records their delivery sequence.
