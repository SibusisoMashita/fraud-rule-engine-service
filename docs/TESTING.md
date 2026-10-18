# Testing

This guide explains how to run the checks, what they cover and how to read the measured results. For the service contract, see [Architecture](ARCHITECTURE.md), [Rules](RULES.md) and [OpenAPI](../api/openapi.yaml).

## Run the tests

Run these commands from the repository root. Java tests need JDK 21 and a running Docker engine; the Maven wrapper downloads Maven for you. PostgreSQL and Redis integration tests use disposable containers and are not silently skipped when Docker is unavailable.

```sh
./mvnw -B -ntp verify
```

`verify` runs the Java unit, contract and integration tests, then packages the application. If Testcontainers cannot find your Docker engine, check the [Docker context setup](OPERATIONS.md#testcontainers-docker-context).

To check the built Docker image and the complete local lifecycle:

```sh
docker compose build app
./scripts/smoke.sh
```

The smoke script creates a separate Compose project, checks two application instances and removes its containers and volumes when it finishes. It uses ports 18080 and 18081. If those ports are occupied, choose two free ports:

```sh
SMOKE_PORT=28080 SMOKE_PEER_PORT=28081 ./scripts/smoke.sh
```

The operational helper tests need Python 3.10 or later and its standard library:

```sh
python3 -B -m unittest discover -s scripts/verification -p 'test_*.py'
```

For recovery, restore, migration and capacity commands, see [Operational verification](OPERATIONS.md#operational-verification). [Security verification](OPERATIONS.md#security-verification) covers the source, dependency and image scans. The [scaling guide](SCALING.md) covers the through-proxy checks and automatic scaling demo. Generated reports belong under ignored `target/`, outside Git history.

## Use Postman

Start the service using the [quickstart](../README.md#quickstart), then import these two files into Postman:

- [Fraud Rule Engine collection](../api/postman/fraud-rule-engine.postman_collection.json)
- [Local environment](../api/postman/local.postman_environment.json)

Select **Fraud — Local** as the environment. Set `baseUrl` to your API address: `http://localhost:8080` for the quickstart, or the app port published by your Compose configuration. Set `apiKey` to the value from `secrets/local-api-key` using its **local value**. Keep the key unshared; masking a value does not make it safe to publish. You can also use a [Postman Local Vault](https://learning.postman.com/docs/sending-requests/postman-vault/postman-vault-secrets/) reference instead.

Choose **Run collection**, use a **local functional run**, keep the requests in their saved order, and run one iteration. The collection checks health, active policy, transaction creation, exact replay, retrieval, filtered pagination and safe error responses. Each run creates two synthetic events with fresh IDs; they remain in the database. No policy settings are changed.

You can also send requests individually. Start with the first transaction creation request before replaying it or running the dependent queries. Its saved payload and response are non-secret collection variables; sending the first creation request again starts a fresh workflow. The example amounts are editable sample data, not fraud-rule thresholds. Checks do not assume a fixed decision or number of rules.

These requests provide a functional API walkthrough. Permission matrices, shared quotas, recovery, load and live policy activation remain covered by the automated suites below. [Postman's runner guide](https://learning.postman.com/docs/tests-and-scripts/running-collections/intro-to-collection-runs/) explains the run controls.

## What the tests cover

**Rules and configuration.** Boundary tests check exact money arithmetic, timestamps, windows, toggles and missing evidence. Independent canonical-hash vectors and distance calculations avoid using the implementation as its own expected answer. A test-only sixth rule runs through Spring and the real engine without changing orchestration. Strict configuration tests reject unknown, duplicate or incompatible settings. Real database tests cover live policy activation, rollback, history, invalid-policy recovery and overlapping selections; each evaluation must keep one complete policy version.

**Protected requests.** Tests cover credential expiry and revocation, live roles and location-source permissions, scope isolation, rotation, safe errors, strict JSON, body/header limits and bounded admission. Two limiter instances share Redis quota state. Dependency stalls, clock changes, expiry, wrong-type data and memory exhaustion must fail safely. Malformed or unauthorized requests must leave no financial records.

**Transactions and history.** Tests call the real Spring transaction boundary and read results through independent PostgreSQL connections. They check replay/conflict responses, duplicate races, customer serialization, complete results, checked and unchecked rollback, deadline/lock timeouts, commit failures and lost acknowledgements. History tests cover currencies, instruments, delayed arrivals and acceptance-sequence cutoffs. Separate 6,000-row fixtures check actual aggregate, small-payment and location query plans without forcing planner settings. These plans describe those fixtures; they are not throughput measurements.

**Physical travel and payment sequences.** Independent GeographicLib reference distances include Cape Town, Johannesburg, Durban, the antimeridian, antipodes and near-pole positions. Tests check accuracy allowances, zero elapsed time, strongest-pair selection, trusted sources, candidate limits and same-instrument matching. Small-payment tests check strict/inclusive amount boundaries, equal-time exclusion and out-of-order arrival. HTTP tests change every business setting live, activate policy during blocked lookups and verify immutable replay.

**Queries and Swagger.** Tests check filters, stable ordering, page bounds, cursor tampering, key rotation and scope binding. A late commit can be missed behind a cursor and found on a fresh traversal; pagination does not promise a snapshot. Migration tests preserve original responses and enforce transaction-owned navigation fields. Swagger uses local assets, does not persist credentials and cannot bypass API authentication.

**Optional policy administration.** Checks cover dedicated permissions, complete policy validation, revision preconditions, concurrent edits, atomic audit and live changes without rewriting saved results. The ordinary runtime retains its database grants. The optional setup helper verifies that ordinary startup creates no administrator secrets, opt-in preserves existing credentials and neither script prints them.

**Docker lifecycle and recovery.** The smoke suite checks fresh setup, all five rules across two processes, shared quotas, replay, signed pagination, live policy changes without restarts, preserved identities, dependency recovery, shutdown, resource restrictions and startup failures. Operational drills kill processes before and after commit, drop a PostgreSQL COMMIT reply, discard an HTTP response and interrupt admitted writes. They check stored effects, released locks, complete results and safe same-ID recovery. A separate restore compares financial, policy and identity fingerprints and reconciles an acknowledged event beyond the backup boundary. Helper tests also check one-shot fault injection and credential redaction.

**Security.** The gate scans source/secrets, verifies SHA-256 coverage for packaged libraries against the CycloneDX inventory, and scans the application, PostgreSQL, Redis, HAProxy and test-only fault-proxy images. All use the same HIGH/CRITICAL and secret thresholds, without a suppression list. Offline source identification is checked with an empty Maven cache; the verified inventory still covers the runtime libraries. See [Security verification](OPERATIONS.md#security-verification).

## Scenario coverage

The IDs below group the acceptance risks. Passing tests apply to the documented v1 scope; they do not mean every possible failure has been tested. Coverage follows the [delivery plan](DELIVERY_PLAN.md).

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
| SC-22 | Backwards wall-clock movement must not exclude accepted history. | Inject clock regression; same-time ordering, acceptance clamping within tolerance and rejection beyond tolerance. |
| SC-23 | Capacity claims must match a measured workload without invariant loss. | Reproducible resource/workload manifest; one/two-instance and hot-customer results; latency, denials, query plans and recovery. |
| SC-24 | Pagination remains authorized and bounded without claiming a snapshot. | Cursor/filter tampering and late commit behind cursor; reconciliation uses point lookups. |
| SC-25 | A reviewer can run the submitted repository without hidden setup. | Fresh-clone build/start/test/stop log; actual repository URL, five-rule samples and persistence after restart. |
| SC-26 | Rule bugs must roll back; required activation audit failures block release. | Throwing rule, audit failure, independent result-count checks and safe same-ID recovery. |
| SC-27 | Time representation must preserve instants, canonical replay and precision. | Equivalent Z/+02:00, cross-midnight/session-zone round trips, excess-precision rejection and travel elapsed-time cases. |
| SC-28 | The real Spring service boundary must commit or roll back atomically. | External bean call outside a test transaction; checked/unchecked and commit-time failures; independent-connection all-or-none reads. |


Rule tests include below/equal/above thresholds and exact window boundaries. Aggregates count the current event once. Policy hashes change with business policy, remain stable when only infrastructure settings change, and reject invalid settings before activation.

## Benchmark specification

The target is **20 ingest requests/second for five minutes on each of one and two instances**, with four authorized principals, all five rules enabled, 100,000 seeded events and 10,000 customers. Each main run has a 15-second warm-up. Separate one-minute runs cover a hot customer at 5/second and mixed traffic at 20/second, with every fifth request a read. A ten-second 300/second overload is followed by recovery. Settings live in [verification configuration](../config/verification.example.json).

A target run passes with p95 <300 ms, p99 <1 second, unexpected errors <0.1%, no dropped or denied arrivals, and no broken financial invariants. After overload, a successful target-rate window must complete its HTTP requests within 60 seconds of the end of overload arrivals, without a restart or quota reset. This includes outstanding response drain and intervening work; checks and report writing after that window's HTTP completion are excluded. Unexpected HTTP/transport errors remain within budget during overload too.

Reports record the hardware, versions, resource limits, policy, quotas, connection budget, seed, arrival model, warm-up, retries and duration. They separate offered, sent, dropped, denied and completed traffic and include latency, locks/pools, query plans and memory. The generator bounds concurrency and overload duration. A failed financial invariant ends the run; sampled memory growth requires review.

### Measured local results

On 2026-10-16, the local benchmark ran on Docker Desktop 29.8.2/aarch64 with ten Docker CPUs and an approximately 8 GiB VM. Each app had one CPU/512 MiB, PostgreSQL one CPU/512 MiB and Redis half a CPU/128 MiB. The run used PostgreSQL 17.11 Alpine 3.24, Redis 8.2.10 Alpine 3.22, one application artifact, the sample policy and existing quotas. Before warm-up, the database held 100,000 seeded events plus seven events from the preceding recovery checks: 100,007 transactions, 100,007 evaluations and 500,035 rule results.

| Profile | Rate / duration | Sent | p95 / p99 | Drops / denials |
| --- | --- | --- | --- | --- |
| One instance | 20/s, 300 seconds | 6,000 | 22.6 / 26.3 ms | 0 / 0 |
| Two instances | 20/s, 300 seconds | 6,000 | 24.1 / 29.1 ms | 0 / 0 |
| One hot customer across two instances | 5/s, 60 seconds | 300 | 40.9 / 66.8 ms | 0 / 0 |
| Mixed reads/writes across two instances | 20/s, 60 seconds | 1,200 | 20.4 / 24.1 ms | 0 / 0 |

Every main, hot-customer and mixed request succeeded, and all checked financial invariants passed. The overload offered 3,000 arrivals: 2,927 were sent, 316 committed, 2,611 received 429 and the bounded generator dropped 73. A full 15-second target window then passed without drops or denials; its HTTP responses completed about 18.8 seconds after overload arrivals ended. There were no unexpected HTTP/transport errors, restarts or quota resets. The final 114,483 evaluations and 572,415 results had no orphan, sequence, watermark or navigation mismatch.

These figures apply to this workload and environment. They do not establish maximum throughput, long-term retention, larger history distributions, thousand-per-second capacity or production SLOs. In particular, 300/second was an overload test, not a supported throughput claim. The harness saves the full manifest and resource samples under `target/operations/`.

## CI and reports

[GitHub Actions](../.github/workflows/verify.yaml) runs the helper and Java suites, Docker build/smoke, the load-balancer/autoscaling checks, recovery and legacy migration. Manual workflow dispatch can also run the full capacity suite. A separate job runs the source, dependency, deployment-image, fault-proxy and secret scans. There is no standalone formatter or SAST engine.

CI keeps operational/scaling results and bounded failure logs, with generated test credentials redacted. It does not upload raw scanner secret findings. Reports identify the command, commit or artifact digest, dependency versions, fixture/policy hashes, environment, UTC time and outcome. Clean-clone runtime checks are recorded separately from CI build success.

## Release checks

The reviewed `83ae32d` passed [Linux GitHub Actions](https://github.com/SibusisoMashita/fraud-rule-engine-service/actions/runs/38978548576) on 2026-10-17: 321 Java tests, 16 helper tests, Docker build and lifecycle, load balancing and automatic scaling, recovery/restore, legacy migration and the full security gate. The optional capacity benchmark was skipped for that push; the measured local benchmark above is separate evidence.

The local review on 2026-10-16 passed 321 Java tests and 16 helper tests, Docker build and lifecycle, extended HAProxy/automatic-scaling checks, recovery, legacy migration, the full security gate and the standard benchmark above.

A fresh public clone at `eedc457`, with the review's documentation and regression-test changes applied, built and started without copying existing secrets or build output. It passed the README create/replay/query workflow, Swagger, live policy activation without replacing the app container, and stop/start persistence. The Postman collection passed two iterations: 34 requests and 170 assertions. Java tests ran in the main review checkout; the clean clone separately verified Docker startup and API behaviour.

The legacy migration drill starts the last pinned Debian database on its own volume and restores into an empty, guarded Alpine volume. It rejects direct legacy-directory reuse, checks UTF-8 and built-in locales, and preserves responses, fingerprints, ownership and API permissions. See [Database image migration](OPERATIONS.md#database-image-migration). The small restore fixture does not establish production recovery-time or data-loss targets.

### Load balancing and local autoscaling

The proxy checks use one HAProxy entrypoint. They cover distribution, authentication/roles, forged forwarding headers, shared quotas, duplicates, live policy changes, failure/removal/recovery, alive-but-unready instances, no-ready rejection and body limits. Writes during drained scale-in keep complete results. Helpers check sustained demand, cooldown, missing readiness/metrics and connection budgets.

The final extended demo completed 240 authenticated reads at eight/second with no drops or denials, used both backends and automatically scaled **one → two → one**. Neither application replicas nor the database published host ports. An unavailable-Docker check also confirmed credential cleanup after a partial startup failure. Reports stay under `target/scaling/`. The benchmark above uses direct application ports; this demo does not measure load-balanced maximum capacity or managed production autoscaling. See [Scaling](SCALING.md).

## Before a real-data deployment

The local checks cover the five rules, live configuration, extensibility, atomic writes, replay, protected access and the measured synthetic failure/load scenarios. They also check that a fresh clone can build, run, test and stop without hidden setup. Repository files exclude credentials, personal data and generated reports.

A real-data deployment still needs a reviewed threat model, TLS/network and identity controls, trusted location sources, retention/privacy/audit rules, calibrated thresholds, human review and operational ownership. It also needs application metrics/alerts, production capacity and availability targets, failover and backup/reconciliation procedures.

This local work does not establish every unknown-commit or network-fault outcome, absolute slow-upload deadlines, complete transaction cancellation, arbitrary old/new rolling-release compatibility, point-in-time recovery or production RPO/RTO. Those boundaries remain explicit deployment work.
