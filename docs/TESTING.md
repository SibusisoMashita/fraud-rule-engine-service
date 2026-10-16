# Testing and acceptance

**Status:** runtime, live policy, protected ingress and transactional/high-amount, velocity, cumulative-spend, impossible-travel and small-payment-sequence flows are implemented and tested. Evaluation paging and active rule-policy inspection are implemented and tested. Operational recovery, restore and the specified one/two-instance capacity workload have passed locally. Application security checks pass; dependency-image security acceptance remains open. Release-wide conformance remains pending. Coverage corresponds to the [delivery milestones](DELIVERY_PLAN.md).

## Implemented verification

Unit/contract tests cover strict policy/limits validation, independent canonical hash vectors, semantic mutations, money/time boundaries, outcome algebra, result completeness and Spring registration of a test-only sixth rule definition. A sixth Spring rule also executes through the real engine without changing orchestration. An explicit HTTP transaction decoder enforces the static domain contract; new-event eligibility and transactional acceptance now use the captured database policy. OpenAPI alignment checks the published enums and extensible IDs, rather than claiming unimplemented HTTP endpoints conform.

Real PostgreSQL/Redis integration tests exercise migrations/no-op reruns, runtime/configuration privileges, live policy selection, activation visibility/rollback/history, invalid-active-policy recovery, pool settings and dependency stalls. An overlapping-selection test preserves captured snapshots and prevents stale work from regressing the cache.

Protected-ingress tests cover credential validity/revocation, live roles/source permissions, immutable scope, rotation, identity audit, safe errors, declared/chunked body limits, strict JSON, duplicate media-header rejection, principal/route quota isolation, bounded admission and permit/context cleanup. Two limiter instances share concurrent Redis quota state; refill caps, backward-clock clamping, idle expiry, wrong-type state, memory exhaustion and dependency recovery are exercised. Real connector assertions verify the projected header/body-discard, thread, connection and idle bounds. Malformed or unauthorized requests admit no financial state; accepted synthetic requests exercise the real flow.

Transactional tests use the real Spring proxy and independent PostgreSQL connections. They cover 201/200/409/404 behaviour, complete stored results, decimal/offset replay, current-policy bypass for existing identity, live thresholds/currencies/scales/amount limits/ingestion windows, scope and instrument isolation, single-effect duplicate races, changed-customer uniqueness recovery, lock timeout/retry, checked/unchecked rule rollback and uncommitted invisibility. Deferred constraints reject orphan/incomplete commits. Tests also cover policy activation before selection and during evaluation, receipt-clock regression, work-budget expiry, actual commit-reserve settings, commit-time rejection and conservative response/cleanup outcomes. An injected lost acknowledgement after a real proxy commit proves recovery finds one committed result; it is not a complete transport-fault drill.

Historical rule tests cover strict count/decimal thresholds, inclusive/exclusive receipt boundaries at microsecond precision, watermark exclusion, missing facts, currency/scope/customer isolation, flagged/delayed history and totals larger than public page limits. Real HTTP tests prove live windows/count limits/spend thresholds/toggles, policy activation during history loading, timeout rollback/retry and concurrent distinct events with consecutive customer watermarks. A 6,000-row PostgreSQL fixture runs EXPLAIN ANALYZE/BUFFERS on the actual bound aggregate queries after VACUUM ANALYZE; covering-index access is verified without forcing planner settings. This is query-access evidence for that synthetic fixture, not a throughput benchmark.

Impossible-travel tests use independently generated GeographicLib spherical inverse reference distances for Cape Town/Johannesburg/Durban, the antimeridian, antipodes, near-pole and coincident positions, plus a WGS84 approximation comparison. They cover strict threshold/allowance boundaries, overlapping accuracy, zero elapsed time, delayed evidence, strongest-pair ties, unavailable facts, candidate overflow, different instruments and scope. Real SQL tests verify inclusive event-time bounds, sequence cutoffs, source/channel/accuracy filters and the actual location-query index plan on 6,000 synthetic rows. HTTP tests exercise live speed/distance/allowance/age/accuracy/filter/cap/toggle changes, captured-policy consistency during lookup, source authorization, timeout rollback/retry and immutable replay. Swagger tests verify packaged contract equality, local assets, nonpersistent credentials, no remote validation and protected API rejection.

Small-payment tests cover strict large-amount, inclusive small-amount and minimum-count boundaries; exact open event-time bounds at microsecond precision; and totals beyond public page limits. Real SQL verifies currency/customer/scope isolation, cross-instrument history, sequence-watermark exclusion and covering-index access on a 6,000-row fixture without planner overrides. HTTP tests cover all live business fields and toggles, flagged history, delayed/equal-time arrivals, immutable replay after later evidence, concurrent small/large arrivals, captured policy during a blocked lookup and timeout rollback/retry. A nonlarge or disabled rule loads no sequence history and does not invent count evidence.

Query tests cover strict parameter decoding, date/page bounds, offset normalization, scope/customer/decision filters, tied timestamp ordering, empty/final pages and signed cursor tampering, scope/filter/page-size binding and key rotation. Real HTTP tests verify pinned default bounds, original evaluation representation, live role revocation, policy hash/settings coherence during activation, safe timeout recovery and a late commit missed behind a cursor but visible on fresh traversal. PostgreSQL fixtures verify ordered key indexes and bounded hydration without forced planner settings. A schema-7 fixture migrates to V8 with the original committed resource unchanged, then proves forged navigation fields are replaced by transaction-owned values and runtime UPDATE remains forbidden. Cursor capacity and invalid signing keys fail closed.

The Compose smoke test covers fresh-volume initialization, the actual migration/seed/activation entrypoints, live business changes without app restart, rejected configuration, preserved policy on restart, dependency recovery, graceful shutdown, resource restrictions, identity-seed preservation, two application containers sharing quota state, a cross-process duplicate race, alternating-instance history totals, live historical thresholds/toggles, cross-process physical travel and live travel policy, cross-process small-payment sequences with all business fields/toggles activated without either app restarting, cross-replica signed pagination and restart continuation, live policy inspection and role separation, invalid cursor-key startup rejection, enabled/disabled Swagger routes, live-threshold decisions, replay/conflict, persisted event retrieval and startup failure gates. It uses disposable resources. The local commands are in [README](../README.md); generated reports stay under `target/`.

The operational harness additionally exercises real dependency outages, blocked-write saturation, process kills during an uncommitted write and after commit, a dropped PostgreSQL COMMIT reply, a caller-discarded HTTP response and SIGTERM during an admitted write. It independently checks stored effects, transaction/lock release, complete results and same-ID recovery. An isolated logical restore compares financial/policy/identity table fingerprints and the original resource, then identifies and replays one acknowledged event beyond the backup boundary. A schema-marker mismatch closes readiness and the real migration entrypoint reruns without changing stored responses. These tests use disposable resources and no production test hooks.

The application security gate verifies source/secrets, exact packaged-library SHA-256 coverage and CycloneDX dependencies, plus OS/secrets in the built image. Tomcat/Jackson and the runtime curl packages are patched. Broader PostgreSQL/Redis OS scans retain unresolved findings; [Operations](OPERATIONS.md#security-verification) records vendor-backed triage and the open dependency-image release gate. A passing application scan does not imply that the complete deployment is approved.

These checks establish the implemented predicates, scoped transaction/history path and tested synthetic recovery boundaries. They do not establish every unknown-commit/network-fault case, absolute upload/transaction cancellation, arbitrary old/new deployment compatibility, production capacity, PITR or production RPO/RTO. Full application metrics/alerts and release-wide conformance remain open.

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

SC identifiers connect scenarios to tests and reports. Contract-level checks provide partial coverage for SC-01, SC-03, SC-14, SC-19 and SC-27; runtime dependency/migration checks contribute to SC-08, SC-09, SC-10 and SC-13. Protected-ingress checks add partial coverage for SC-01, SC-02, SC-10, SC-11, SC-12 and SC-14. The first transactional flow adds evidence for SC-01–05, SC-07, SC-09, SC-13–14, SC-17, SC-19, SC-22 and SC-27–28; acknowledgement-loss modelling contributes to SC-06. Historical-rule boundary, concurrency and live-policy checks add evidence for SC-03–05, SC-12, SC-14, SC-17 and SC-19; their synthetic query plans contribute to SC-23 without establishing capacity. Travel boundary and live-policy tests add evidence for SC-03, SC-14, SC-17 and SC-18. Payment-sequence boundary, ordering, concurrency and live-policy tests add evidence for SC-03–05, SC-14, SC-17 and SC-21. Query/policy checks add evidence for SC-02, SC-13, SC-14, SC-17 and SC-24. Operational drills add evidence for SC-05–10, SC-12–13 and SC-15–16; workload measurements contribute to SC-23. Broader release scenarios remain open. The matrix covers identified risks rather than every possible failure.

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

The target is **20 ingest requests/second for five minutes on each of one and two instances**, four authorized principals, all five rules enabled, 100,000 seeded events and 10,000 customers. A 15-second warm-up precedes each main profile. Separate one-minute profiles cover one hot customer at 5/second and a read/write mix at 20/second with every fifth request a read. A ten-second 300/second overload precedes bounded recovery. Parameters are in [verification configuration](../config/verification.example.json). Acceptance targets are p95 <300 ms, p99 <1 second, unexpected errors <0.1% and zero invariant failures. Capacity denials during the target run mean the offered-rate target was not met.

Benchmark reports identify hardware, JVM/container limits, versions, policy, quotas, pool/replica budget, seed, arrival model, warm-up, retries and duration. Results distinguish offered, sent, dropped, denied and completed traffic alongside latency, lock/pool waits, DB plans and memory. Generator concurrency and overload duration are bounded. Recovery acceptance is a successful target-rate window within 60 seconds from the end of overload arrivals, including response drain and intervening verification overhead, without restart or quota reset. Unexpected HTTP/transport errors must remain within the error budget even during overload. Invariant loss ends a run; container limits bound resources and sampled growth requires review. Higher rates, long-term retention, production SLOs and thousand-per-second capacity are outside this benchmark.

### Local qualification

The 2026-10-16 run used Docker Desktop 29.8.2 on aarch64, ten Docker CPUs and an approximately 8 GiB VM. Each app had one CPU/512 MiB, PostgreSQL one CPU/512 MiB and Redis half a CPU/128 MiB. All profiles used the same patched image, unchanged sample policy and existing quotas. The fixture contained 100,000 transactions, 100,000 evaluations and 500,000 synthetic rule results before warm-up.

| Profile | Rate / duration | Sent | p95 / p99 | Drops / denials |
| --- | --- | --- | --- | --- |
| One instance | 20/s, 300 seconds | 6,000 | 24.5 / 28.0 ms | 0 / 0 |
| Two instances | 20/s, 300 seconds | 6,000 | 19.6 / 23.5 ms | 0 / 0 |
| One hot customer across two instances | 5/s, 60 seconds | 300 | 31.3 / 46.0 ms | 0 / 0 |
| Mixed reads/writes across two instances | 20/s, 60 seconds | 1,200 | 20.6 / 24.7 ms | 0 / 0 |

All main/hot/mixed target requests succeeded; every checked financial invariant passed. The ten-second 300/s overload offered 3,000 arrivals: 2,947 were sent, 316 committed, 2,631 returned 429 and 53 arrivals were dropped by the bounded generator. Recovery then passed a full 15-second target window with no drops/denials, completed approximately 19 seconds after the overload arrival window ended, including response drain and intervening verification overhead, without app restart or quota reset. No unexpected HTTP/transport errors occurred. Final state contained 114,476 complete evaluations and 572,380 results with no orphan, sequence, watermark or navigation mismatch. The overload rate is not a supported throughput claim.

These results qualify this workload and environment. Maximum throughput, larger history distributions and production SLOs remain unmeasured. The command and full manifest/resource samples are produced by the harness under ignored `target/operations/`; generated reports are not checked in.

## CI and evidence

The current workflow runs Maven unit/contract and real-dependency integration tests, then Docker build/smoke. Operational helper tests and disposable Docker recovery checks run in CI; workflow dispatch can add the full capacity suite. A separate security job runs the pinned source/dependency/image and secret checks. Generated operational results are retained as CI artifacts; raw secret findings are not uploaded. Formatting/static analysis remains separate release work. Feature checks combine focused tests with regression coverage. Multi-instance, failure, load and restore suites cover relevant changes and release validation.

Evidence identifies the command, commit/artifact digest, dependency versions, fixture/policy hashes, environment, UTC timestamp, outcome and report location. Reports exclude credentials and personal data. Clean-clone runtime verification is separate from CI build success.

## Release checks

- [ ] Supported compatible dependencies/images are pinned; README build/run/test/stop instructions match the submitted revision.
- [ ] Five rules, configuration validation, extensibility and SC-01–28 have appropriate passing evidence; contract examples match runtime responses.
- [ ] Duplicate/concurrency/unknown-commit, two-instance quota, outage, migration/policy activation, shutdown and restore/reconciliation checks pass.
- [ ] Benchmark passes or an evidence-backed scope adjustment is explicitly documented; scan findings are reviewed with no unresolved exploitable high/critical issue.
- [ ] Fresh-clone demonstration succeeds; supported behaviour and limitations match the submitted revision; repository and artifacts contain no secrets or personal data.

Real-data deployment acceptance additionally covers the threat model, identity/TLS/network controls, least privilege, location provenance, retention/privacy/audit policy, calibrated thresholds and human review, operational ownership, capacity/SLO, failover and backup/RPO/RTO/reconciliation procedures. These are separate from the demonstration release criteria.
