# Testing and acceptance

**Status:** runtime foundation tests are implemented. Business/security/concurrency/load and financial recovery acceptance remain pending. Coverage corresponds to the [delivery milestones](DELIVERY_PLAN.md).

## Foundation verification

`./mvnw -B -ntp verify` runs five integration tests against real PostgreSQL and Redis: exact health responses, denied routes/no session cookies, restricted runtime SQL privileges, incompatible schema readiness, and dependency stalls with recovery. Migrations are applied twice to verify the second pass is a no-op. Dependencies are paused during fault tests so their randomly mapped host ports stay stable.

`./scripts/smoke.sh` exercises the actual Compose configuration: first-volume database initialization, the one-shot migration entrypoint, dependency stop/start recovery, persisted schema after restart, graceful shutdown, migration startup gating, Redis authentication/command restrictions and application container restrictions. It uses disposable resources. CI runs both layers; local reports remain generated artifacts under `target/`.

These checks establish the foundation only. They do not establish fraud correctness, authorization scope, limiter behaviour, financial persistence, production capacity or successful backup restoration.

## Test boundaries

| Layer | Purpose |
| --- | --- |
| Unit | Deterministic rule boundaries, money/time arithmetic, policy validation, canonical requests and decision outcomes. Expected values are independent of the implementation, including reference distances. |
| Integration | Real PostgreSQL and Redis through Testcontainers: SQL, migrations, locks, constraints, rollback and shared quotas. PostgreSQL-specific behaviour is exercised against PostgreSQL. |
| Contract | [OpenAPI](../api/openapi.yaml) conformance, actual responses, invalid requests, authorization and version compatibility. |
| End-to-end | Build and start from a clean clone; exercise all five rules, clear/partial results, retrieval, replay, restart persistence and safe stop. |
| Resilience/security/capacity | Controlled failures, abuse, two application instances, overload and isolated restoration using synthetic data and throwaway credentials. |

The contracts under test are defined in [Architecture](ARCHITECTURE.md), [Rules](RULES.md) and OpenAPI.

## Scenario coverage

SC identifiers connect scenarios to tests and reports. Foundation health/dependency and migration checks provide partial coverage for SC-08, SC-09, SC-10 and SC-13. No scenario below is fully accepted yet. The matrix covers identified risks rather than every possible failure.

| ID | Risk / acceptance invariant | Required evidence |
| --- | --- | --- |
| SC-01 | Invalid input must not create accepted state. | Malformed, deep, oversized, nonfinite and precision fixtures; safe errors; independent DB absence checks. |
| SC-02 | Unauthorized or cross-scope access must not disclose or write data. | Credential/role matrix; forged scope/proxy, enumeration and masking tests. |
| SC-03 | Identical replay returns the original evaluation. | Canonicalization tests; replay after age, currency, policy and credential changes; one event/evaluation. |
| SC-04 | Concurrent or conflicting duplicates produce one accepted effect. | Two-instance barrier races, including changed customer; equivalent replay or conflict; row/count assertions. |
| SC-05 | Deadline exhaustion before confirmed commit is bounded and safe. | Held-lock/slow-query tests; confirmed rollback before claiming not accepted; resource-release metrics. |
| SC-06 | Lost commit acknowledgement leaves an unknown outcome. | Commit-path network fault; authoritative same-ID lookup/replay after recovery resolves one effect. |
| SC-07 | Lost response after commit must not cause a second effect. | Drop committed response; same-ID POST/GET returns original policy/build evidence after policy change. |
| SC-08 | Process termination must not leave partial state. | Kill before/during/after writes and commit; restart, FK/result checks and measured graceful shutdown. |
| SC-09 | Database outage or pool starvation must not leak resources. | Stop DB/exhaust pool; bounded errors, readiness/liveness behaviour, recovery and same-ID replay. |
| SC-10 | Redis faults must not open unmetered access. | Timeout, OOM and script failures; bounded rejection/readiness, recovery and no limiter bypass. |
| SC-11 | Replicas and credential rotation must share principal quotas. | Concurrent two-instance quotas; rotation, clock jumps and Redis restart; document quota reset behaviour. |
| SC-12 | Abusive traffic and saturation must remain bounded. | Oversize, slow-client, hot-customer and overload runs; memory/queue/pool limits, denials and recovery trace. |
| SC-13 | Failed migrations or incompatible deployments must not become ready. | Migration/checksum failure, old/new compatibility, rollback/forward-fix drill with preserved data. |
| SC-14 | Invalid policy or credentials must block activation. | Unknown/duplicate settings, currency gaps, all-disabled policy, failed smoke/audit and traffic-readiness checks. |
| SC-15 | Restore must preserve consistency and disclose its recovery boundary. | Timed isolated restore; known IDs, hashes, FKs and counts; identify acknowledged events beyond the boundary. |
| SC-16 | Missing upstream events cannot be inferred from received events alone. | Producer manifest with deliberate gaps; point reconciliation and same-ID replay; report changed recovery decisions. |
| SC-17 | Only supported booked debits contribute, with exact money arithmetic. | Reject pending, refund and reversal events; stable economic identity, scale/range and currency tests. |
| SC-18 | Impossible travel uses trusted same-instrument distance and elapsed time. | Independent Cape Town/Johannesburg/Durban fixtures, plausible flights, equal-time pairs/triples, different instruments and source permissions. |
| SC-19 | Missing or inapplicable evidence must not silently become no-match. | Complete decision/status truth table; physical missing evidence, first reference and online cases. |
| SC-20 | Candidate limits and delayed locations must not hide travel evidence. | Cap/cap+1, inclusive horizon, late earlier/later/equal-time candidates; immutable as-observed results. |
| SC-21 | Small-payment bursts follow event time, not arrival order. | Threshold equalities, same-time exclusion, reversed arrival order, delayed small/large and currency/scope cases. |
| SC-22 | Backwards wall-clock movement must not exclude accepted history. | Inject clock regression; same-time ordering, acceptance clamp and drift signal assertions. |
| SC-23 | Capacity claims must match a measured workload without invariant loss. | Reproducible resource/workload manifest; one/two-instance and hot-customer results; latency, denials, query plans and recovery. |
| SC-24 | Pagination remains authorized and bounded without claiming a snapshot. | Cursor/filter tampering and late commit behind cursor; reconciliation uses point lookups. |
| SC-25 | A reviewer can run the submitted repository without hidden setup. | Fresh-clone build/start/test/stop log; actual repository URL, five-rule samples and persistence after restart. |
| SC-26 | Rule bugs must roll back; required activation audit failures block release. | Throwing rule, audit failure, independent result-count checks and safe same-ID recovery. |
| SC-27 | Time representation must preserve instants, canonical replay and precision. | Equivalent Z/+02:00, cross-midnight/session-zone round trips, excess-precision rejection and travel elapsed-time cases. |
| SC-28 | The real Spring service boundary must commit or roll back atomically. | External bean call outside a test transaction; checked/unchecked and commit-time failures; independent-connection all-or-none reads. |

Rule coverage includes below/equal/above thresholds, exact window boundaries and the current event counted once for aggregates. Configuration tests cover schema compatibility, unknown/duplicate keys, invalid windows/caps, monetary gaps and contradictory thresholds. Policy hashes must be canonical, change with fraud policy, and remain stable when only operational settings change. A test-only sixth rule exercises registration without engine changes or a closed identifier list.

## Benchmark specification

The initial target is **20 ingest requests/second for five minutes**, four authorized principals, all five rules enabled, 100,000 seeded events and 10,000 customers. Separate profiles cover one and two application instances, a hot customer at 5/second and a bounded read/write mix. Acceptance targets are p95 <300 ms, p99 <1 second, unexpected errors <0.1% and zero invariant failures. Capacity denials during the target run mean the offered-rate target was not met.

Benchmark reports identify hardware, JVM/container limits, versions, policy, quotas, pool/replica budget, seed, arrival model, warm-up, retries and duration. Results distinguish offered, sent, dropped, denied and completed traffic alongside latency, lock/pool waits, DB plans and memory. Generator concurrency and overload duration are bounded. Recovery acceptance is return to target-rate latency/queues within 60 seconds without restart or quota reset. Invariant loss or unsafe resource growth ends a run. Higher rates, long-term retention, production SLOs and thousand-per-second capacity are outside this benchmark.

## CI and evidence

The verification pipeline covers Maven Wrapper verification, formatting/static checks, contract validation, real-dependency integration tests, Docker build/smoke and secret/dependency/image scans. Feature checks combine focused tests with regression coverage. Multi-instance, failure, load and restore suites cover relevant changes and release validation.

Evidence identifies the command, commit/artifact digest, dependency versions, fixture/policy hashes, environment, UTC timestamp, outcome and report location. Reports exclude credentials and personal data. Clean-clone runtime verification is separate from CI build success.

## Release checks

- [ ] Supported compatible dependencies/images are pinned; README build/run/test/stop instructions match the submitted revision.
- [ ] Five rules, configuration validation, extensibility and SC-01–28 have appropriate passing evidence; contract examples match runtime responses.
- [ ] Duplicate/concurrency/unknown-commit, two-instance quota, outage, migration/policy activation, shutdown and restore/reconciliation checks pass.
- [ ] Benchmark passes or an evidence-backed scope adjustment is explicitly documented; scan findings are reviewed with no unresolved exploitable high/critical issue.
- [ ] Fresh-clone demonstration succeeds; supported behaviour and limitations match the submitted revision; repository and artifacts contain no secrets or personal data.

Real-data deployment acceptance additionally covers the threat model, identity/TLS/network controls, least privilege, location provenance, retention/privacy/audit policy, calibrated thresholds and human review, operational ownership, capacity/SLO, failover and backup/RPO/RTO/reconciliation procedures. These are separate from the demonstration release criteria.
