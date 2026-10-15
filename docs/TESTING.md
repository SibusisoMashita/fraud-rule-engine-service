# Testing and acceptance

**Status:** runtime, live policy, protected ingress and transactional/high-amount, velocity and cumulative-spend flows are implemented and tested. Impossible travel, the payment sequence, complete endpoint conformance, full failure/recovery drills and measured capacity remain pending. Coverage corresponds to the [delivery milestones](DELIVERY_PLAN.md).

## Implemented verification

Unit/contract tests cover strict policy/limits validation, independent canonical hash vectors, semantic mutations, money/time boundaries, outcome algebra, result completeness and Spring registration of a test-only sixth rule definition. A sixth Spring rule also executes through the real engine without changing orchestration. An explicit HTTP transaction decoder enforces the static domain contract; new-event eligibility and transactional acceptance now use the captured database policy. OpenAPI alignment checks the published enums and extensible IDs, rather than claiming unimplemented HTTP endpoints conform.

Real PostgreSQL/Redis integration tests exercise migrations/no-op reruns, runtime/configuration privileges, live policy selection, activation visibility/rollback/history, invalid-active-policy recovery, pool settings and dependency stalls. An overlapping-selection test preserves captured snapshots and prevents stale work from regressing the cache.

Protected-ingress tests cover credential validity/revocation, live roles/source permissions, immutable scope, rotation, identity audit, safe errors, declared/chunked body limits, strict JSON, duplicate media-header rejection, principal/route quota isolation, bounded admission and permit/context cleanup. Two limiter instances share concurrent Redis quota state; refill caps, backward-clock clamping, idle expiry, wrong-type state, memory exhaustion and dependency recovery are exercised. Real connector assertions verify the projected header/body-discard, thread, connection and idle bounds. Malformed or unauthorized requests admit no financial state; accepted synthetic requests exercise the real flow.

Transactional tests use the real Spring proxy and independent PostgreSQL connections. They cover 201/200/409/404 behaviour, complete stored results, decimal/offset replay, current-policy bypass for existing identity, live thresholds/currencies/scales/amount limits/ingestion windows, scope and instrument isolation, single-effect duplicate races, changed-customer uniqueness recovery, lock timeout/retry, checked/unchecked rule rollback and uncommitted invisibility. Deferred constraints reject orphan/incomplete commits. Tests also cover policy activation before selection and during evaluation, receipt-clock regression, work-budget expiry, actual commit-reserve settings, commit-time rejection and conservative response/cleanup outcomes. An injected lost acknowledgement after a real proxy commit proves recovery finds one committed result; it is not a complete transport-fault drill.

Historical rule tests cover strict count/decimal thresholds, inclusive/exclusive receipt boundaries at microsecond precision, watermark exclusion, missing facts, currency/scope/customer isolation, flagged/delayed history and totals larger than public page limits. Real HTTP tests prove live windows/count limits/spend thresholds/toggles, policy activation during history loading, timeout rollback/retry and concurrent distinct events with consecutive customer watermarks. A 6,000-row PostgreSQL fixture runs EXPLAIN ANALYZE/BUFFERS on the actual bound aggregate queries after VACUUM ANALYZE; covering-index access is verified without forcing planner settings. This is query-access evidence for that synthetic fixture, not a throughput benchmark.

The Compose smoke test covers fresh-volume initialization, the actual migration/seed/activation entrypoints, live business changes without app restart, rejected configuration, preserved policy on restart, dependency recovery, graceful shutdown, resource restrictions, identity-seed preservation, two application containers sharing quota state, a cross-process duplicate race, alternating-instance history totals, live historical thresholds/toggles, live-threshold decisions, replay/conflict, persisted event retrieval and startup failure gates. It uses disposable resources. The local commands are in [README](../README.md); generated reports stay under `target/`.

These checks establish the three implemented predicates and scoped transaction/history path. They do not establish impossible-travel/payment-sequence correctness, every unknown-commit/network-fault case, total upload/transaction cancellation, production capacity or successful backup restoration.

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

SC identifiers connect scenarios to tests and reports. Contract-level checks provide partial coverage for SC-01, SC-03, SC-14, SC-19 and SC-27; runtime dependency/migration checks contribute to SC-08, SC-09, SC-10 and SC-13. Protected-ingress checks add partial coverage for SC-01, SC-02, SC-10, SC-11, SC-12 and SC-14. The first transactional flow adds evidence for SC-01–05, SC-07, SC-09, SC-13–14, SC-17, SC-19, SC-22 and SC-27–28; acknowledgement-loss modelling contributes to SC-06. Historical-rule boundary, concurrency and live-policy checks add evidence for SC-03–05, SC-12, SC-14, SC-17 and SC-19; their synthetic query plans contribute to SC-23 without establishing capacity. Broader release scenarios remain open. The matrix covers identified risks rather than every possible failure.

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

The current workflow runs Maven unit/contract and real-dependency integration tests, then Docker build/smoke. Formatting/static checks and automated secret/dependency/image scans remain release-pipeline work. Feature checks combine focused tests with regression coverage. Multi-instance, failure, load and restore suites cover relevant changes and release validation.

Evidence identifies the command, commit/artifact digest, dependency versions, fixture/policy hashes, environment, UTC timestamp, outcome and report location. Reports exclude credentials and personal data. Clean-clone runtime verification is separate from CI build success.

## Release checks

- [ ] Supported compatible dependencies/images are pinned; README build/run/test/stop instructions match the submitted revision.
- [ ] Five rules, configuration validation, extensibility and SC-01–28 have appropriate passing evidence; contract examples match runtime responses.
- [ ] Duplicate/concurrency/unknown-commit, two-instance quota, outage, migration/policy activation, shutdown and restore/reconciliation checks pass.
- [ ] Benchmark passes or an evidence-backed scope adjustment is explicitly documented; scan findings are reviewed with no unresolved exploitable high/critical issue.
- [ ] Fresh-clone demonstration succeeds; supported behaviour and limitations match the submitted revision; repository and artifacts contain no secrets or personal data.

Real-data deployment acceptance additionally covers the threat model, identity/TLS/network controls, least privilege, location provenance, retention/privacy/audit policy, calibrated thresholds and human review, operational ownership, capacity/SLO, failover and backup/RPO/RTO/reconciliation procedures. These are separate from the demonstration release criteria.
