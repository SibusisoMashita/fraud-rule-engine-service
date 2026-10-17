# Architecture

The service is a Java modular monolith backed by PostgreSQL and Redis. All five rules, protected ingestion, evaluation queries, live policy inspection and the Docker scaling demo are implemented. [Testing](TESTING.md) records the checks and remaining deployment limits. See [Operations](OPERATIONS.md) for runtime commands, [Rules](RULES.md) for fraud logic and [OpenAPI](../api/openapi.yaml) for the HTTP contract.

## Application boundaries

HTTP controllers and their exception advice live in `ingress`: `IngressController` handles the API and `HealthController` handles probes. Subpackages group request decoding and deadlines (`ingress.request`), authentication and shared quotas (`ingress.security`), and HTTP problem responses (`ingress.error`). The API adapters authenticate callers, validate requests and enforce quotas. Application services coordinate use cases and transactions. Pure domain rules evaluate immutable facts without making network or database calls. Infrastructure adapters handle SQL, policy loading and Redis. Repository interfaces describe specific persistence operations.

Follow an ingestion request through [IngressController](../src/main/java/za/co/sibusiso/fraud/ingress/IngressController.java), [TransactionService](../src/main/java/za/co/sibusiso/fraud/evaluation/TransactionService.java), [TransactionalEvaluation](../src/main/java/za/co/sibusiso/fraud/evaluation/TransactionalEvaluation.java) and [RuleEngine](../src/main/java/za/co/sibusiso/fraud/evaluation/RuleEngine.java).

Spring JDBC keeps scoped queries, aggregates and row locks explicit. One HikariCP DataSource and JdbcTransactionManager coordinate writes. PostgreSQL owns transactions, evaluations, identities and policy; Redis holds abuse quotas. There is no broker or external event publication in v1, so no outbox is needed.

## How an evaluation is saved

1. Ingress checks transport limits, credentials, permissions and the shared principal/route quota. Credentials determine scope and which location sources the caller may supply.
2. The decoder validates and normalizes the request. The transaction service hashes its canonical representation and looks up `(scope,eventId)`. Equivalent input returns the original result; changed content returns a conflict. This lookup comes before current age/currency policy checks, so a retry can still recover an old result. Static safety and current authorization still apply.
3. New events pass lifecycle validation. Inside a `READ COMMITTED` transaction, the writer creates customer state if needed, locks `(scope,customerId)` with `FOR UPDATE` and checks event identity again. It holds only one customer lock per transaction.
4. The writer inserts or verifies the immutable instrument/customer binding. It samples database wall time after acquiring the lock, sets `acceptedAt = max(clock_timestamp(), lastAcceptedAt)` and advances `customerSequence`. Clock regressions within the startup-configured tolerance are clamped; larger regressions reject the new event. New-event age and skew checks use the original wall-time sample; it is not persisted separately.
5. The writer captures the active database policy and validates new-event currency, amount and age against that snapshot and database time. The preceding customer sequence becomes `contextHighWatermark`. Enabled rules declare the history they need; the JDBC adapter loads it sequentially under the same lock and deadline. Every rule uses the captured policy and facts.
6. Event, evaluation, rule results and customer state commit together. The response is returned only after commit completes. A unique-event loser exits its aborted transaction before one bounded primary lookup; writes are never automatically retried.

This provides one committed evaluation per retained scoped event ID, assuming stable producer IDs, database durability and the same locking protocol for every writer/import. It cannot recognize one payment submitted under different IDs. Different customers can run concurrently; one customer's history deliberately stays serial. `READ COMMITTED` alone does not provide that ordering—the customer lock does.

A public `@Transactional` method on a separate injected bean owns the write boundary. The coordinator calls through Spring's proxy with explicit `READ_COMMITTED` isolation and `rollbackFor=Exception.class`. Errors propagate and participating writes share the transaction. A nontransactional facade handles recovery after commit or rollback has finished. See [Spring transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) and [PostgreSQL isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

Deferred constraints enforce event/evaluation pairing and complete rule results. Runtime can insert immutable records and update customer state; it cannot update or delete events, evaluations, results or instrument bindings. Stored producer ID, permission revision and source-permission snapshot explain the admitted provenance without saving credentials. Responses come from the committed representation, including policy/build IDs and explicit disabled-rule results. Packaged build identity hashes the executable JAR; class-directory runs hash compiled artifact files.

A confirmed rollback returns a known `NOT_ACCEPTED` error. Uncertain commit or transaction completion returns `UNKNOWN`; losing the HTTP response cannot establish that the payment was rejected. Callers recover with the same ID/payload or a scoped GET. [Testing](TESTING.md) describes the process-kill, dropped-acknowledgement and lost-response checks and their limits.

### Loading history

Rules declare typed history requirements. The JDBC adapter loads each distinct requirement once; disabled rules request no work, and missing required facts fail the operation instead of becoming zero.

- **Velocity and cumulative spend:** receipt-time SQL uses `accepted_at > windowStart`, `accepted_at <= currentAcceptedAt` and `customer_sequence <= contextHighWatermark`. Each rule adds the current event once. Counts and same-currency sums include flagged history and do not depend on API pagination. Scoped covering indexes support these queries.
- **Impossible travel:** a typed location query filters scope, instrument ownership, event time, source, channel, accuracy and watermark. It reads at most the live candidate limit plus one to detect overflow. The rule selects pairs; `GreatCircleDistance` handles clamped spherical geometry. A startup safety ceiling bounds the live candidate limit.
- **Small-payment sequence:** only a current amount strictly above its currency's large threshold requests `SmallPaymentsQuery`. One aggregate returns count and exact total across the scoped customer's instruments, using the same currency, inclusive small-amount maximum, open event-time window and watermark. Prior decisions do not filter history.

All SQL runs sequentially inside the write transaction using decreasing statement/lock budgets. The exact rule predicates and uncertainty outcomes are in [Rules](RULES.md).

## Queries and policy inspection

Evaluation reads use a separate read-only Spring transaction behind a nontransactional facade. Dependency or completion errors therefore return safe retryable failures without implying financial writes. Bound parameters enforce scope, customer, decision and acceptance-time filters, with configured date/page limits. A materialized key query selects at most page size plus one before loading rule evidence. Point and page reads use the same stored representation.

Database-owned customer/acceptance-time fields on evaluations are derived from the transaction at insert; runtime cannot update them. Scoped `(acceptedAt,id)`, customer and decision indexes support navigation. These fields and indexes cost storage and write work; synthetic query plans do not establish production capacity.

Versioned HMAC-SHA256 cursors bind scope, normalized explicit filters, page size, resolved dates and the last key. A dedicated startup signing secret is shared across replicas. Signature verification precedes decoding. Cursors are readable, grant no authority and require fresh authentication and READ quota on every page. Default dates use the database clock on the first page only.

Pagination is a live traversal, not a snapshot: each page opens a new read transaction, and a late commit behind the last key can be missed. Reconciliation uses producer manifests and scoped ID lookups after outstanding work settles.

Policy inspection uses the same live provider as ingestion. It returns one captured hash and typed rule settings, including disabled rules, under POLICY permission and a separate quota. It excludes secrets, source YAML and infrastructure settings. Reading policy cannot activate it or change stored evaluations. Retrospective re-evaluation and export are deferred.

## Data ownership and representation

Producers own categories, economic debit identity and physical-evidence provenance. Categories are validated and stored, but the current five rules do not vary by category. Producers sharing a scope need collision-free event IDs. Only `BOOKED` outgoing debit observations enter history. Authorization/capture updates, incoming credits, refunds and reversals are rejected. A later reversal does not erase the original observed debit; this service does not maintain a settlement or net-spend ledger.

| Record | What it guarantees |
| --- | --- |
| `transactions` | Unique `(scope,eventId)`; normalized input/hash and canonicalization version; customer/instrument/type/channel, money and times; unique scoped customer sequence |
| `instrument_owners` | Immutable `(scope,instrumentId)` customer binding, enforced by a composite foreign key |
| `customer_processing_state` | Last acceptance time and sequence, updated under its scoped customer lock |
| `evaluations` | One per event, with policy hash, evaluator build ID, context watermark, decision, coverage and evaluation time |
| `rule_results` | One per `(evaluation,ruleId)`, with algorithm version, status/reason and bounded versioned evidence |
| `policy_versions` | Immutable policy source identified by canonical content hash; excludes secrets and operational limits |
| `policy_head` / `policy_activations` | One active reference and append-only activation history, changed atomically |

Money uses `BigDecimal` and PostgreSQL `numeric(20,4)`. Currency scales/maxima fit that storage; excess precision fails before SQL. Sums use unrestricted `numeric`, remain currency-specific and never convert currencies. Database constraints enforce required fields, positive/finite/range checks, allowed types/statuses/channels, uniqueness and references.

Input timestamps require an offset. `OffsetDateTime` normalizes to `Instant`; pgJDBC maps UTC `OffsetDateTime` to `timestamptz(6)`. Offset-free and sub-microsecond values are rejected, and server-local time is never inferred. See [pgJDBC time mapping](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes).

R2/R3 use acceptance time; R4/R5 use event time over already observed history. The watermark explains what was known when evaluating; it cannot prove that upstream delivery was complete. Indexes support scoped receipt windows, customer/currency event-time sequences, instrument travel history and retrieval. Aggregate cost remains a capacity concern even with bounded results. Retention/deletion needs a separate design because it changes identity and history guarantees.

## Live policy and stable identities

[Business policy](../config/policy.example.yaml) and [startup settings](../config/service.example.yaml) are separate. PostgreSQL holds immutable policy versions, one active reference and append-only activation history. The configuration identity activates policy through a serialized database function; runtime has SELECT only.

Every selection reads the committed active hash. It may reuse a cached typed version only for that exact hash; there is no polling delay or fallback on database/configuration failure. **Business changes require no app restart.** An evaluation keeps the policy it started with. Operational limits require validated startup configuration. [Operations](OPERATIONS.md) explains activation and recovery.

Validation rejects unknown/duplicate fields or rule IDs, invalid relationships, currency gaps, incompatible schemas and all-disabled policies. Every enabled rule needs a matching implementation, algorithm/settings types and enough evidence capacity. The sample enables all five rules. Dependency injection discovers typed definitions, implementations and history requirements without rule-name branches in the coordinator. See [adding a rule](RULES.md#adding-a-rule).

Canonicalization v1 applies throughout `/api/v1`, including policy and code releases. Changing it requires an explicit API/identity compatibility design. The hash is SHA-256 over UTF-8 `namespace + "\n" + canonicalJson`, with one LF separator. Namespaces are `fraud-policy-v1` and `fraud-request-v1`.

Canonical JSON follows these service-specific rules, rather than RFC 8785:

- Object keys sort by Java String order with no formatting whitespace. Numbers use plain decimals, remove trailing zeros and normalize zero to `0`; numeric tokens are at most 32 characters. Policy/transaction amounts are normalized decimal strings.
- Policy source/channel sets sort by enum name; other arrays retain their defined order. Text is preserved without trimming, case folding or Unicode normalization.
- Policy identity includes schema/canonicalization versions, currencies, ingestion eligibility, stable rule IDs, algorithm versions and all settings, including disabled rules. It excludes operational settings, formatting and activation metadata.
- Request identity includes scope and every admitted field. Instants use UTC with at most microsecond precision; absent location is null. Invalid calendar dates, offset-free timestamps and UTC years outside 0001–9999 are rejected.

Request identity stays independent of current policy eligibility so retries can be recognized first. Independent fixed vectors protect the format from accidental changes.

OpenAPI is the single HTTP specification and is packaged unchanged for Swagger UI. Pinned local WebJar assets disable remote validation and credential persistence. Startup configuration controls optional public documentation GET routes; protected APIs keep their authentication, permissions and quotas.

## Security and bounded work

Credentials contain generated 256-bit secrets in `keyId.secret` form. Only SHA-256 digests are stored; verification uses constant-time comparison. Each authentication reads PostgreSQL for the enabled principal, validity/revocation, immutable scope, roles and source-permission version. Database time controls validity. There is no credential cache, so revocation and permission changes need no restart. Rotated keys retain the principal's quota identity.

Ingest, READ, POLICY and physical-source permissions are separate. Headers cannot grant scope or location trust. A request retains its admitted permission snapshot; revocation blocks later admissions. The security administrator manages identities with immutable principal scope and credential ownership/hash, automatic permission revisions and transactionally coupled audit entries. Runtime has SELECT only. Rotation creates a new key ID and revokes the old key; seed jobs preserve administrator changes. Credentials and real customer/card data stay out of Git, images and logs.

A bounded Redis Lua script atomically enforces principal/route token buckets using Redis TIME. Configured principals bound key cardinality; invalid keys create no buckets. Refill time never moves backwards, tokens are capped, state expires and Redis uses `noeviction`. Dependency/script/memory errors close protected routes. Restart, state loss or forward clock jumps may replenish allowance; recovery resumes after health returns. These are abuse quotas, not durable financial limits.

The HTTP adapter rejects duplicate/unknown/missing fields, scalar coercion, invalid UTF-8, trailing JSON, unsupported content types/encodings and invalid static transaction/location facts. Declared and streamed bodies are bounded. Source permissions are checked before dispatch. Health routes do not need authentication or quotas. A nonblocking per-replica permit bounds admitted work and is released on every exit. Connector limits bound workers, connections, socket backlog, headers, discarded bodies and idle waits. Safe Problem responses include generated correlation IDs and `no-store`, without credentials or parser/SQL diagnostics.

Deadlines are cooperative. The work budget spans authentication, limiting, decoding and processing, with a commit reserve. The writer applies decreasing local statement/lock limits, checks time around rule execution and reserves time for deferred commit checks. Spring's transaction timeout includes processing plus that reserve. Pool, statement and socket limits each bound a part of the request; none proves noncommit after a lost acknowledgement. See [PostgreSQL timeout semantics](https://www.postgresql.org/docs/current/runtime-config-client.html).

A trickling client can outlast an idle socket timeout, so an external edge must bound total header/body read time and pre-authentication abuse. The optional [HAProxy setup](SCALING.md#readiness-aware-routing) supplies configured proxy bounds. Complete upload/transaction cancellation and resource release under every network fault are not established. Queries bind SQL values and reject unknown/duplicate filters; regex only validates syntax.

Local Compose publishes the app on loopback and keeps PostgreSQL/Redis internal. Containers run non-root with resource limits, separate migration/runtime identities, no runtime DDL and injected secrets. Scans cover dependencies, images and secrets. Real-data deployment also requires managed identity/network controls, certificate-verified TLS across trust boundaries, encrypted storage/backups, edge/DDoS limits and approved access/audit/retention policies. See [OWASP REST security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html).

## Deployment and recovery

A timeout, disconnect or 5xx can follow commit. Treat an ambiguous result as `UNKNOWN`; retrieve or replay the same ID/payload after bounded jittered backoff. Even a temporary 404 cannot prove that an in-flight first attempt will not commit. A replacement ID would lose replay protection. Rule bugs roll back the operation; missing evidence is an explicit rule outcome.

Flyway runs once per release using the migration identity. Readiness requires compatible schema, valid expected policy and dependencies. It checks technical readiness; operators still approve releases after smoke checks. Policy activation commits a new hash while evaluations already running finish under their captured version.

Code/algorithm or policy-schema changes need a compatible release. Drain ingestion when old/new builds cannot read the same active policy. Rolling releases require tested old/new schema and contract compatibility. Roll back an image only when the schema permits it; otherwise use a reviewed forward fix, never a destructive down-migration.

Shutdown budgets satisfy `work deadline < app shutdown grace < container grace`. Liveness is independent of shared dependencies. Readiness and routing drain on shutdown; SIGKILL relies on database recovery. Correlation IDs and stored build/policy IDs support diagnosis. The operational harness samples container CPU/memory and PostgreSQL connections/activity/lock waits; dedicated application histograms, pool/limiter counters and alert integrations remain deployment work.

Restore checks cover isolated recovery, row/FK/hash/sequence reconciliation and producer gap reporting. A logical dump restore is not PITR. Replaying missing events may produce different acceptance-time decisions; lost evaluations cannot be reconstructed exactly. Real deployment needs approved RPO/RTO, durable upstream replay, recovery ownership and an availability design.

## Scaling and future alternatives

[Scaling](SCALING.md) explains the shared-state design, HAProxy routing and bounded Docker autoscaling demo. Every app and rollout process must fit the database budget:

```text
(replicas + rollout surge) × pool maximum ≤ application connection budget
```

A pool acquisition timeout bounds waiting for a connection, not query execution. Hikari acquisition/validation settings also have interdependent limits; see [HikariCP configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby).

Connection arithmetic alone does not prove throughput. For example, 1,000 requests/second borrowing a connection for a mean 0.15 seconds needs a mean 150 concurrent connections before read traffic and variance. One hot customer still serializes. [Testing](TESTING.md) records the measured workload; thousand-per-second capacity is unverified.

Query/lock/CPU/IO/commit measurements should justify extra replicas, summaries, partitions or shards. Fraud history and duplicate recovery read the primary because asynchronous replicas can lag.

A broker would need a new delayed-result contract, per-customer ordering, bounded consumers/pools, poison/gap handling and replay rules. Database commit before offset acknowledgement can redeliver; identity deduplication remains necessary, and Kafka transactions do not automatically include PostgreSQL. An outbox becomes relevant if committed decisions must be published externally. Neither is a v1 dependency.
