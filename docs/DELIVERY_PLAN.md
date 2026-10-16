# Delivery plan

Stages 0–10 are implemented. Local release-candidate qualification passed from a fresh public clone with the candidate changes applied: Java/helper tests, Docker build, README lifecycle, full smoke, recovery, legacy database migration, bounded capacity and the complete application/PostgreSQL/Redis security gate. The PostgreSQL image-family guard requires a logical restore for earlier Debian installations. Deployment acceptance for real data remains separate, as defined in [Testing](TESTING.md).

## Milestones

| Stage | Deliverable | Acceptance evidence |
| --- | --- | --- |
| 0. Design foundation | Service scope, architecture, rule specifications, draft API and configuration | Consistent contracts, valid document links and schema examples |
| 1. Runtime foundation | Java/Maven application, pinned dependencies, Docker/Compose, PostgreSQL/Redis, Flyway, CI and operational runbook | Clean startup, migrations, health checks, graceful shutdown and reproducible setup |
| 2. Configuration and contracts | Typed startup limits, live versioned database policy, canonical hashes, decimal/time models and outcome algebra | Invalid-setting rejection, atomic live activation/rollback, stable hashes, boundary fixtures and contract alignment |
| 3. Protected ingress | Scoped authentication/authorization, trusted-source permissions, shared quotas and bounded request processing | Access-isolation, validation, quota and dependency-failure tests |
| 4. First evaluation flow | Atomic persistence, idempotency, customer locking, high-amount rule and event retrieval | POST-to-GET flow, replay/conflict handling, rollback, duplicate races and lost-response recovery |
| 5. Historical rules | Velocity and cumulative debit spend | Window/currency boundaries, multi-instance history correctness and query measurements |
| 6. Impossible travel | Trusted physical evidence, distance/time/uncertainty and bounded candidates | Plausible flights, equal-time/delayed events, different instruments, bounded candidates, live policy and missing-reference cases; packaged Swagger access checks |
| 7. Payment sequence | Small-payment burst followed by a large payment | Open event-time/amount/count boundaries, currency/scope/watermark isolation, live policy, concurrent arrivals, rollback/replay and two-instance Docker checks |
| 8. Evaluation queries | Authorized filtered keyset pages and active-policy inspection | Strict filter/range tests, scope-bound signed cursors, indexed page plans, migration preservation, live policy inspection and cross-instance/restart Docker checks |
| 9. Operational verification | Recovery, deployment compatibility, resource limits and measured capacity | Fault/kill/migration/restore drills, two-instance tests, benchmark reports and security scans |
| 10. Release validation | Complete usage documentation, examples and limitations | Clean-clone build/run/test demonstration and the release criteria in [Testing](TESTING.md) |
| 11. Load-balanced ingress (planned) | One entrypoint routing to two application instances, readiness-aware routing and bounded proxy configuration | Docker checks through the entrypoint: both instances serve traffic, unavailable/unready instances stop receiving new requests, recovery restores routing, graceful drain, shared quotas, duplicate safety and complete stored results; proxy image security scan |
| 12. Autoscaling (future, optional) | Platform-managed application replica scaling after stage 11, application metrics and versioned scaling configuration | Through-proxy scale-out/scale-in, readiness and graceful drain, stable behaviour under changing demand, missing-metric/dependency-failure checks and preserved financial/connection/quota invariants |

Stages build on the preceding contracts and runtime capabilities. Related implementation, configuration, tests and documentation form one reviewable change. The [testing plan](TESTING.md) defines the detailed acceptance scenarios; the table above records their delivery sequence.

## Planned load-balancer change

Stage 11 is a separate implementation commit after release validation. Proposed title: `feat: add readiness-aware load balancing`.

The change adds a pinned, resource-bounded proxy and a repeatable two-instance Compose setup with one loopback entrypoint. Proxy timeouts, request-size limits, health checks and upstream settings live in deployment configuration. Application instances remain internal to that setup; PostgreSQL, Redis, policy activation and application authorization retain their existing responsibilities. Proxy forwarding headers are handled explicitly and cannot grant identity or scope. Automatic retries of transaction POSTs are disabled; uncertain outcomes retain the same-ID recovery contract.

Acceptance runs through the proxy rather than alternating application ports. It checks distribution, readiness removal/recovery, draining during shutdown, bounded rejection when no backend is ready, shared rate limits and concurrent duplicate requests. Evidence includes response consistency and database invariants, proxy/container security scans and a fresh Docker build/start/test/stop demonstration. Normal and failure-path checks precede any claim of load-balanced capacity. Autoscaling and shared-database high availability remain separate deployment work.

[Scaling](SCALING.md) explains the current design and the planned routing layer. The implementation commit includes its configuration, meaningful Docker tests and updates to the existing run/test instructions; generated evidence remains under `target/`.

## Future autoscaling change

Stage 12 is an optional deployment milestone after load balancing, documented separately from the assessment's implemented capabilities. Proposed title: `feat: add bounded application autoscaling`.

It introduces a selected deployment platform, the metrics required for its scaling signal, and versioned configuration for minimum/maximum replicas, targets and stabilization. Maximum replicas plus rollout surge must fit the shared database connection budget. Scaling changes application count only; database policy activation and caller quotas retain their existing semantics. Platform and capacity settings remain unselected until deployment requirements and measurements justify them.

Acceptance observes scale-out and scale-in through the public entrypoint, new-instance readiness, graceful removal, bounded behaviour when metrics or dependencies fail, and unchanged idempotency, policy consistency, complete results and shared quotas. Evidence records workload, replica changes, latency, rejections and database connections. [Scaling](SCALING.md#how-we-would-add-autoscaling) explains the implementation sequence and boundaries. This milestone adds no claim that autoscaling is currently implemented or tested.
