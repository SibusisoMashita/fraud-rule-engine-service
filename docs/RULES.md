# Fraud rules

All five rules use the active, versioned PostgreSQL policy. Thresholds, windows and enabled states change through [policy activation](OPERATIONS.md) without restarting the app. The [sample policy](../config/policy.example.yaml) supplies illustrative starting values.

Each evaluation captures one policy version. Every enabled rule returns a result; an unavailable check never becomes a successful nonmatch. Only valid `BOOKED` outgoing debits enter history, including previously flagged events. History stays within the caller's scope. Money uses decimal arithmetic; distance calculations use bounded floating point.

## R1 HIGH_AMOUNT

Flags a payment when `amount > currency threshold`. Equality does not match. Every supported currency needs a threshold plus valid amount/scale bounds; the evaluator has no Java default.

Evidence schema 1 stores `amount`, `threshold` and `currency`. An unsupported currency returns 422 for a new event. An identical retry returns its original result before current policy eligibility is checked.

## R2 TRANSACTION_VELOCITY

Flags a customer when `count > maxTransactions` within the configured receipt-time window.

The count includes prior events with `acceptedAt` in `(currentAcceptedAt - window, currentAcceptedAt]`, through `contextHighWatermark`, then adds the current event once. All accepted currencies and older-policy history count. With no history, the count is one. SQL aggregates the history independently of API pagination.

Evidence schema 1 stores `count`, `maxTransactions`, `windowSeconds` and `contextHighWatermark`.

## R3 CUMULATIVE_SPEND

Flags a customer when `total > currency threshold`. Equality does not match.

The total includes prior same-currency amounts in its own configured receipt-time window, using the same open-start/closed-end boundaries as R2 and the captured `contextHighWatermark`. It then adds the current amount once. There is no currency conversion. This measures observed gross debits; reversals do not reduce the total, and it is not an account balance.

Evidence schema 1 stores `total`, `threshold`, `currency`, `windowSeconds` and `contextHighWatermark`.

## R4 IMPOSSIBLE_TRAVEL

Compares trusted physical observations for the **same instrument**, rather than combining all of a customer's cards. Coordinates determine distance; optional city labels are only display text.

### When it applies

`CARD_PRESENT` and `ATM` can establish physical presence. `ECOMMERCE`, and physical channels excluded by policy, return `NOT_APPLICABLE`. Eligible channels require coordinates, accuracy radius, event time, a trusted source and producer permission. Missing or overly uncertain evidence returns `INSUFFICIENT_DATA`. A caller claiming an unauthorized location source receives 403.

Admission records producer ID and source-permission version without credentials. Later revocation does not rewrite history, but historical evidence must still satisfy the captured policy's source/channel/accuracy filters. Source and channel must agree. The app relies on upstream terminal assertions; IP/VPN location, merchant headquarters and shipping addresses are not accepted as physical presence.

### Which observations are compared

Candidates are prior committed physical events for the same scoped instrument/customer, through the captured watermark, where:

```text
abs(candidate.occurredAt - current.occurredAt) ≤ maxComparisonAge
```

This includes equal timestamps and later event-time neighbours already received through delayed delivery. SQL orders by `(occurredAt,eventId)` and loads `maxCandidates + 1` to detect overflow. Overflow returns `INSUFFICIENT_DATA / CANDIDATE_LIMIT`, even if the prefix contains a suspicious pair. The live cap must fit the startup `api.maxLocationCandidatesPerEvaluation` ceiling. Comparing only the nearest observation could miss contradictory or equal-time evidence.

### Distance and time

Haversine calculates great-circle distance from WGS84-style latitude/longitude using a spherical approximation and a fixed mathematical Earth-radius constant. Intermediate values are clamped to valid ranges.

```text
effectiveDistance = max(0, distance - accuracyA - accuracyB)

MATCH when both are true:
  effectiveDistance > minDistanceKm
  effectiveDistance > maxSpeedKmh × absoluteElapsedHours + travelAllowanceKm
```

The same inequality works at zero elapsed time without dividing by zero. Any violating pair yields `MATCH`. Otherwise, at least one eligible candidate yields `NO_MATCH`; no candidate yields `INSUFFICIENT_DATA / NO_REFERENCE`.

Evidence identifies the pair with the greatest excess over the travel bound, breaking ties by ID, whether or not it matches. It records candidate count, distance, effective distance, elapsed seconds, configured limits, both accuracy radii, watermark and evidence schema version. Measurements round to six decimal places for display; decisions use unrounded values. Historical coordinates and city labels stay out of rule evidence. The returned current transaction retains its admitted location.

This is a fraud signal, not proof of travel. Lower limits can flag legitimate flights; shared/cloned cards, multiple devices and bad terminal metadata remain risks. Producers must supply reliable event times—ingestion age/skew checks cannot establish terminal-clock accuracy. Delayed evidence does not recompute past evaluations. Cape Town/Johannesburg/Durban fixtures test the algorithm, not bank-grade detection accuracy.

## R5 SMALL_PAYMENT_BURST_THEN_LARGE

Looks for several small payments followed by a large payment, using event time rather than delivery order.

If `current amount <= large threshold`, the result is `NO_MATCH / AMOUNT_NOT_LARGE` and no sequence history is loaded. Otherwise, count committed events that have:

- The same scope, customer and currency, across all of the customer's instruments.
- `amount <= smallPaymentMaximum`.
- `occurredAt` in `(currentOccurredAt - window, currentOccurredAt)`, through the captured watermark.

The current event and equal-time candidates are excluded because their event order is unknown. The rule matches when `count >= minSmallPayments`: small-amount equality counts, large-amount equality does not qualify, and count equality matches.

One SQL aggregate returns the count and exact decimal total without loading rows or applying API page limits. Flagged events and older-policy history remain eligible when their facts satisfy the captured policy. A delayed large payment cannot count later-occurring small payments as preceding it. Small payments received afterwards do not rewrite the large payment's stored evaluation.

Evidence schema 1 stores current amount, currency, both thresholds, minimum count, window and watermark. Large payments also record count and total; nonlarge payments omit those unqueried facts. Window, count, per-currency thresholds and enabled state all change through live policy. The pattern alone cannot establish card testing; failed authorizations are outside scope.

## Result algebra

Rule statuses are `MATCH`, `NO_MATCH`, `NOT_APPLICABLE`, `INSUFFICIENT_DATA` and `DISABLED`. Each evidence object carries `evidenceSchemaVersion`. V1 has no severity, weighted score or fraud probability.

| Enabled outcomes (other complete nonmatches omitted) | Decision | Coverage |
| --- | --- | --- |
| `NO_MATCH` | `CLEAR` | `COMPLETE` |
| `NO_MATCH` + `NOT_APPLICABLE` | `CLEAR` | `COMPLETE` |
| `INSUFFICIENT_DATA`, with no match | `INDETERMINATE` | `PARTIAL` |
| `MATCH` | `FLAGGED` | `COMPLETE` |
| `MATCH` + `INSUFFICIENT_DATA` | `FLAGGED` | `PARTIAL` |
| Multiple matches | `FLAGGED` | `COMPLETE` unless another enabled rule lacks evidence |
| `DISABLED` + `NO_MATCH` | `CLEAR` | `COMPLETE` |

Any match flags the event. Without a match, missing evidence makes the decision indeterminate. Otherwise it is clear: no applicable evaluated rule matched, which does not prove absence of fraud. Coverage is partial exactly when an enabled rule has insufficient data. `NOT_APPLICABLE` is a completed applicability check; disabled rules do not participate. All-disabled policy is invalid. Complete evidence does not prove complete upstream delivery.

Unexpected rule errors return 500 and roll back; transient dependency/context failures return 503. Neither saves a normal partial evaluation. One pure aggregation function implements the table. New events use the captured active policy; identical retries return the original result. A future severity model would need defined combination/missing-data semantics, calibration and API compatibility review.

## Adding a rule

Register a typed `RuleDefinition` with a stable ID, algorithm version, configuration type and validator. Implement `FraudRule` with typed settings, immutable context, an evidence-size declaration and pure evaluation. Enabled rules declare any typed receipt aggregates, location observations or small-payment summaries they need. The writer loads distinct requirements under the customer lock; missing facts fail the operation. Disabled rules load nothing.

Dependency injection registers the rule without adding name-based branches to the engine. New facts may also require context-provider, API or schema changes. Add regression cases and increment the algorithm version when its logic changes. A test-only sixth Spring rule demonstrates registration and execution without loader/orchestrator edits or a closed five-rule API enum; it also checks that a disabled rule is not evaluated.
