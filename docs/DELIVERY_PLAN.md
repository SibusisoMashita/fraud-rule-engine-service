# Delivery plan

Stages 0–11 are implemented and tested locally. [Testing](TESTING.md) records the results, measured workload and remaining deployment limits. The PostgreSQL image-family guard requires a logical restore for older Debian data directories; see [Operations](OPERATIONS.md#database-image-migration).

## Milestones

| Stage | Deliverable | How it is checked |
| --- | --- | --- |
| 0. Design foundation | Scope, architecture, rule definitions, draft API and configuration | Consistent contracts, working document links and schema examples |
| 1. Runtime foundation | Java/Maven, pinned dependencies, Docker/Compose, PostgreSQL/Redis, Flyway, CI and runbook | Reproducible startup, migrations, health and graceful shutdown |
| 2. Configuration and contracts | Typed startup limits, live database policy, canonical hashes, money/time models and decisions | Invalid settings, atomic activation/rollback, stable hashes and boundary cases |
| 3. Protected ingress | Scoped auth/permissions, trusted sources, shared quotas and bounded requests | Access isolation, input validation, quotas and dependency failures |
| 4. First evaluation | Atomic storage, idempotency, customer locking, high amount and retrieval | POST-to-GET, replay/conflict, rollback, duplicate races and lost responses |
| 5. Historical rules | Velocity and cumulative spend | Window/currency boundaries, multi-instance history and query measurements |
| 6. Impossible travel | Trusted coordinates, distance/time/uncertainty, bounded candidates and Swagger | Flights, equal-time/delayed events, instruments, candidate limits, live policy, missing references and packaged docs |
| 7. Payment sequence | Small-payment burst followed by a large payment | Amount/count/time boundaries, isolation, live policy, concurrency, rollback/replay and two-instance Docker checks |
| 8. Evaluation queries | Authorized filtered pages and active-policy inspection | Strict filters, signed cursors, indexes, migration preservation, live policy and cross-instance/restart use |
| 9. Operational verification | Recovery, compatibility, resource limits and measured capacity | Fault/kill/migration/restore drills, two-instance workloads, benchmarks and scans |
| 10. Release validation | Usage docs, examples and limits | Clean-clone build/run/test and the [release checks](TESTING.md) |
| 11. Load balancing and Docker scaling demo | One entrypoint, readiness routing, bounded proxy and local autoscaling | Routing/removal/recovery, drain, shared quotas, duplicates, complete stored results and proxy scan |
| 12. Autoscaling (future, optional) | Managed platform scaling, app metrics and versioned scaling settings | Through-proxy scale-out/in, readiness/drain, changing demand, missing metrics/dependency failures and preserved data/connection/quota guarantees |

Each stage builds on the earlier contracts. Related code, configuration, tests and documentation belong in one reviewable change. Generated reports stay under `target/`; they are not repository documentation.

## Load balancing and Docker scaling demo

Stage 11 adds pinned HAProxy, a two-instance Compose overlay and a disposable automatic-scaling demo alongside the single-instance setup. [Scaling](SCALING.md) explains how to run and observe it.

The proxy provides one loopback entrypoint and routes only to ready apps. Its timeouts, body limits, checks and upstream behaviour live in deployment configuration. The apps retain authentication, shared quotas and database policy handling. Forwarding headers cannot grant scope or identity. Transaction POSTs are not automatically retried; uncertain results use the same-ID recovery contract.

Checks run through the proxy and cover both ready instances, unavailable/unready removal and recovery, drain, no-ready rejection, shared limits, duplicate requests and complete database results. The busy/quiet demo verifies measured automatic scale-out and drained scale-in within configured bounds, cooldown and connection budget. Proxy scanning and fresh Docker build/start/test/stop checks complete the local validation. This does not establish maximum proxy throughput, managed production autoscaling or shared-database high availability.

## Separate assessment UI

The UI is a separate React and TypeScript project. Its planned screens are transaction scenarios, saved evaluations and policy editing. It uses the real API and shares its exact-decimal, permission and recovery contracts; Docker scaling controls stay in the existing scripts.

| Check-in | Deliverable | Verification |
| --- | --- | --- |
| Backend policy administration | Opt-in protected API, conditional activation, narrow writer identity and complete policy contract | Permissions, invalid candidates, stale edits, live changes, audit, unchanged runtime grants and Docker startup |
| UI foundation | Capitec-branded navigation, connection handling, evaluation/policy reads, Docker and CI | Real API access, empty/error states, responsive keyboard navigation and clean startup |
| Transaction playground | Editable scenarios for all five rules, saved evidence and replay/recovery | Sequential history, exact decimals, conflicts and uncertain outcomes |
| Policy editor | Shared form/JSON draft, validation, change preview and activation | Concurrent edits, live cross-instance policy changes and fresh-clone browser walkthrough |

The backend administration API is the first check-in. The frontend screens and repository remain planned until their respective changes are delivered. Existing permissions and the ordinary quickstart remain the default.

## Future autoscaling change

Stage 12 is optional deployment work. It needs a selected platform, secured app metrics and versioned minimum/maximum replicas, targets and stabilization settings. Maximum replicas plus rollout surge must fit the shared database connection budget. Scaling changes app count; live policy and caller quotas keep their existing behaviour.

Validation must observe scale-out/in through the entrypoint, new-instance readiness, graceful removal and bounded behaviour when metrics or dependencies fail. It must preserve idempotency, consistent policy snapshots, complete results and shared quotas. Reports should capture workload, replica changes, latency, rejections and database connections. [Scaling](SCALING.md#verification-and-production-autoscaling) explains the boundary between the working Docker demo and this future deployment.
