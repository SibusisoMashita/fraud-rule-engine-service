# Architecture

Target design for v1. Related specifications: [scope](../README.md), [HTTP contract](../api/openapi.yaml), [fraud rules](RULES.md) and [acceptance criteria](TESTING.md).

## 1. One application with explicit boundaries

The service is designed as a modular monolith: API adapters handle HTTP/authentication/validation, application services own use cases and transaction boundaries, pure domain rules evaluate immutable facts, and infrastructure adapters own SQL/configuration/Redis. Rules operate on supplied facts without I/O; repository interfaces represent specific persistence operations.

Spring JDBC/JdbcClient provides explicit scoped SQL, aggregates and row locks. One HikariCP DataSource and JdbcTransactionManager coordinate the write path. JPA/Hibernate is unnecessary for this workflow. Redis stores abuse quotas only; PostgreSQL is the authority for events and evaluations. There is no broker or external publication in v1, so no outbox is required.

## 2. Ingestion and committed-effect guarantee

1. Ingress applies transport/parser bounds, authentication, authorization and the shared principal/route quota. Credentials establish scope and allowed location sources.
2. The fixed v1 request is decoded and decimals/timestamps are canonicalized. Existing `(scope,eventId)` is checked before current new-event age/currency policy applies. Equivalent input returns the stored result; changed content conflicts. Authorization remains current.
3. A new event passes lifecycle/policy validation before a READ COMMITTED transaction begins. Customer state is inserted if absent, then `(scope,customerId)` is locked with `FOR UPDATE`. Identity is checked again after acquisition. Each transaction holds only one customer lock.
4. The immutable instrument-to-customer mapping is inserted or verified. Database wall time is sampled after lock acquisition; `acceptedAt=max(clock_timestamp(),lastAcceptedAt)` and customerSequence advances. The sampled wall time remains available for drift diagnostics.
5. One policy snapshot and the preceding customerSequence establish the evaluation contextHighWatermark. Bounded historical facts feed all enabled rules. Event, evaluation, rule results and updated state share one transaction.
6. A successful response follows commit completion. A unique-event race aborts the transaction; winner lookup/comparison occurs in a fresh transaction. Failed transactions have no further queries or automatic write retries.

One committed evaluation per retained scoped event ID depends on stable producer identity and authoritative DB durability. It is not exactly-once network delivery or deduplication of a real payment delivered under different IDs. The guarantee depends on every writer/import using the same lock protocol. READ COMMITTED alone does not serialize history; the customer lock does. Distinct customers proceed concurrently; one hot customer intentionally serializes.

The write boundary is a public `@Transactional` processing method on a separate injected Spring bean, with explicit READ_COMMITTED and rollbackFor=Exception.class. The coordinator calls through its proxy; participating writes share the transaction, errors propagate, and DB work stays sequential. A nontransactional facade handles recovery after the proxy has completed commit/rollback. [Spring transaction mechanics](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html), [PostgreSQL isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

## 3. Data ownership and representation

The producer owns category, economic debit identity and physical evidence provenance. Producers sharing a scope use a collision-free identity namespace. Only BOOKED debit observations enter history; authorization/capture lifecycle updates, incoming credits, refunds and reversals are rejected. A later reversal does not erase an observed original debit. This service is not a settlement/net-spend ledger.

| Record | Invariant |
| --- | --- |
| transactions | Unique(scope,eventId); normalized input/hash and canonicalization version; customer/instrument/type/channel; money and timestamps; unique scoped customerSequence |
| instrument_owners | Unique(scope,instrumentId); immutable customer binding enforced by a composite foreign key |
| customer_processing_state | Scoped customer primary key; lastAcceptedAt and sequence updated under its row lock |
| evaluations | Exactly one per event; policy hash, evaluator build ID, context watermark, decision, coverage and evaluation time |
| rule_results | Unique(evaluation,ruleId); algorithm version, status/reason and bounded versioned evidence; configured result completeness verified in tests/reconciliation |
| rulesets | Immutable canonical policy identified by content hash; secrets/operational limits excluded |

Money uses BigDecimal and PostgreSQL numeric(20,4). Currency scale/maxima are bounded by storage; excess precision fails validation before SQL. Sums use unrestricted numeric and remain currency-specific. Database constraints cover NOT NULL, positive/finite/range, type/status/channel, uniqueness and referential integrity.

Input timestamps carry an offset via OffsetDateTime and normalize to Instant for domain/canonical identity. The pgJDBC adapter maps UTC OffsetDateTime to timestamptz(6). Offset-free and sub-microsecond inputs fail validation; server-local time is not inferred. Fixed canonicalization v1 applies throughout /api/v1, including releases/policy changes. A new normalization contract requires explicit API/identity compatibility design. [pgJDBC time mapping](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes).

R2/R3 use acceptance time; R4/R5 use event time over already observed history. The watermark and stored facts explain what was known, not whether upstream events were missing. Indexes cover scoped customer acceptedAt for receipt windows, scoped customer occurredAt/currency for sequence queries, scoped instrument occurredAt for travel, and scoped acceptedAt/ID for retrieval. Aggregate query cost remains a capacity risk even with bounded results. Retention/deletion is deferred because it affects identity/history guarantees.

## 4. Configuration and API consistency

Immutable [fraud policy](../config/policy.example.yaml) and [operational settings](../config/service.example.yaml) are separate. Startup validation rejects unknown/duplicate fields or rule IDs, invalid relationships, currency gaps, incompatible schema and all-disabled policy. Registry injection discovers implementations; engine code does not switch on rule names. Each evaluation retains policy hash plus algorithm/build identity. Operational tuning does not change the fraud-policy hash.

OpenAPI defines the HTTP contract and its conformance-test baseline. Existing retries bypass current new-event eligibility, not static safety or authorization. Cursor pages are bounded live traversal, not a stable reconciliation snapshot: a concurrent late commit can be missed behind a cursor. Reconciliation uses producer manifests and scoped ID lookups after outstanding work settles. Retrospective re-evaluation/export is deferred; original evaluations stay immutable.

## 5. Security and bounded work

Assessment authentication uses generated high-entropy service keys supplied at runtime, digest/constant-time verification and stable principal/scope mapping. Rotation preserves quota identity. Credential expiry/revocation and controlled reload are acceptance cases. Ingest/read/policy roles and allowed evidence sources are separate. Each SQL lookup/aggregate/constraint is scoped; customer IDs or client tenant headers do not authorize access. Credentials and real customer/card data are excluded from Git, images and logs.

Redis atomically enforces principal/route token buckets. Configured principals bound key cardinality; invalid keys create no buckets. Refill time is nondecreasing, tokens are capped, and state has TTL with noeviction memory policy. Dependency/script/memory errors fail protected routes closed; restart/state loss or forward clock jumps may replenish abuse allowance. Assessment recovery resumes after health returns with an alert; this is not a durable financial limit.

Input controls comprise typed/allowlisted values, bounded identifiers/body/depth/numbers, duplicate-key rejection, SQL parameters and allowed query filters. Regex is syntax validation, not authorization or SQL injection prevention. Queues, active requests, pools, query windows/candidates and driver/dependency deadlines have configured bounds. The remaining deadline budget spans all steps and includes a commit reserve. Individual statement/lock/pool limits are not a total request timeout; cancellation acceptance covers worker/lock release as well as the HTTP response. [PostgreSQL timeout semantics](https://www.postgresql.org/docs/current/runtime-config-client.html).

Local Compose exposes only the app on loopback; DB/Redis stay internal. Non-root container, constrained resources, separate migration/runtime DB identities, no runtime DDL, injected secrets and dependency/image/secret scans form the runtime deployment baseline. External real-data deployment additionally needs managed identity/network controls, certificate-verified TLS across trust boundaries, encrypted storage/backups, edge/DDoS limits and operator-approved access/audit/retention policies. API keys alone are not the production security design. [OWASP REST security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html).

## 6. Failures, deployment and recovery

Timeout/disconnect/5xx may occur after commit. An ambiguous result is UNKNOWN; recovery uses GET or the same ID/payload after bounded jittered backoff. Even a temporary 404 cannot prove an in-flight first attempt will not commit. A replacement event ID would lose the replay guarantee. A rule bug aborts processing; missing evidence is a normal explicit rule outcome.

Flyway executes once per release with migration identity; application readiness requires compatible schema, valid expected policy and dependencies. Traffic readiness remains closed until protected smoke checks and release audit pass. Policy changes deliberately drain old ingestion, wait for in-flight work, switch one expected hash, smoke and resume. Code-only rolling releases require tested old/new schema/contract compatibility. Image rollback is allowed only on compatible schema; otherwise reviewed forward fix, never blind destructive down-migration.

The shutdown budget satisfies work deadline < app shutdown grace < container grace. Liveness is independent of shared dependencies. Readiness/routing drains on shutdown; SIGKILL still relies on DB transaction recovery. Observability comprises safe correlation/build/policy records, low-cardinality latency/error/rule/pool/lock/limiter metrics and actionable alerts. Operational commands are outside the current design-stage artifact.

Restore acceptance covers isolated recovery, row/FK/hash/sequence reconciliation and producer gap reporting. A logical dump restore is not PITR. Replayed missing events may produce different acceptance-time decisions; lost original evaluations cannot be reconstructed exactly. Real-data deployment requires approved RPO/RTO, durable upstream replay, recovery ownership and availability architecture.

## 7. Capacity and deferred alternatives

Independent-customer parallelism is constrained by the shared DB budget, including every pool and old/new rollout process: `(replicas+surge) × poolMax <= applicationConnectionBudget`. Acquisition timeout limits waiting for a connection, not query execution. Hikari validation/acquisition settings have interdependent bounds. [HikariCP configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby).

For illustration only: DB ceiling 200 minus 40 reserved/other clients leaves app budget 160. With two surge processes, 10/20/30 steady replicas allow arithmetic pool ceilings 13/7/5. Pool max 10 permits at most 14 steady plus two surge. These are connection limits, not recommendations or throughput evidence. At hypothetical 1,000/s and mean 0.15 seconds of total borrowed-connection time per ingest, mean demand is 150 connections before read traffic/variance; a hot customer remains serial regardless of replica count. The acceptance workload and proof requirements live in Testing.

Query/lock/CPU/IO/commit measurements determine whether additional replicas, summaries, partitions or shards are justified. Fraud history and duplicate recovery use the primary because asynchronous replicas can lag. Thousand-per-second capacity is unverified.

A broker becomes relevant only if requirements justify durable backlog/delayed results. It would require a new acceptance/status contract, per-customer partition ordering, bounded consumers/DB pools, poison/gap handling and replay semantics. DB commit followed by offset acknowledgement can redeliver; identity deduplication remains necessary. Kafka transactions do not automatically include PostgreSQL. An outbox would become relevant if DB-committed decisions gained an external publication requirement. None is a v1 dependency.
