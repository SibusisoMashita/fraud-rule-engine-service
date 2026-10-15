# Fraud rules

Rule specification for v1. Business thresholds come from [the policy example](../config/policy.example.yaml); values are illustrative. Every enabled rule contributes a result. Only valid BOOKED outgoing debit events enter history, including previously flagged ones. All history is scope-isolated. Financial calculations use decimal arithmetic; geodesic math uses bounded floating point.

## R1 HIGH_AMOUNT
MATCH when current amount > currency threshold. Equality is NO_MATCH. Every supported currency has a configured threshold and amount/scale bounds. Unknown currency for a new event is 422; an identical existing retry bypasses current policy eligibility.

## R2 TRANSACTION_VELOCITY
The count includes this customer's previous accepted events with acceptedAt in (currentAcceptedAt-window,currentAcceptedAt], through contextHighWatermark, plus the current event once. MATCH when count > maxTransactions. All supported currencies count. Empty history means count=1. The count is a database aggregate, independent of retrieval pagination.

## R3 CUMULATIVE_SPEND
The sum includes same-currency amounts for the same scoped customer and receipt window, through contextHighWatermark, plus the current event once. MATCH when sum > configured threshold. No FX conversion. This is observed gross debit activity, not net spending after reversals or a balance.

## R4 IMPOSSIBLE_TRAVEL
Location comparisons are scoped to the same instrument, rather than all cards belonging to a customer. CARD_PRESENT and ATM channels are applicable. ECOMMERCE is NOT_APPLICABLE; it does not establish physical presence. Physical channels require a trusted source, coordinates, accuracy radius, occurredAt and producer permission. Missing evidence or excessive uncertainty is INSUFFICIENT_DATA. An unauthorized producer claiming a privileged location source is rejected 403, not silently trusted. City labels are optional display text; coordinates determine distance.

Admission captures trust eligibility as producer ID and source-permission version, without credentials; later revocation does not rewrite history. Existing evidence must also satisfy the current policy's allowed source/channel/accuracy. Upstream verified terminal location is an assertion; this app cannot prove it. Source/channel consistency is validated. No IP/VPN, merchant headquarters or shipping address is accepted as physical presence.

The candidate set contains all eligible prior committed physical events for this instrument with |candidate.occurredAt-current.occurredAt| <= maxComparisonAge, including equal timestamps and later event-time neighbors from delayed delivery. Ordering is (occurredAt,id); a maxCandidates+1 query detects overflow. Overflow produces INSUFFICIENT_DATA/CANDIDATE_LIMIT. Nearest-neighbor-only selection is insufficient because it can miss equal-time or contradictory evidence.

Distance uses Haversine great-circle calculation on WGS84-style lat/lon, with a spherical approximation and fixed mathematical earth-radius constant. Intermediate values are clamped to valid mathematical ranges. effectiveDistance=max(0,distance-accuracyA-accuracyB). For each pair, MATCH only when effectiveDistance > minDistanceKm AND effectiveDistance > maxSpeedKmh*absoluteElapsedHours+travelAllowanceKm. At zero elapsed time, the distance inequality applies without division. Any matching pair yields MATCH; otherwise NO_MATCH if at least one eligible candidate exists; no candidate means INSUFFICIENT_DATA/NO_REFERENCE.

Stored evidence identifies the strongest violating pair (largest excess, tie-break ID), candidate count, distance, effective distance, elapsed seconds, configured limits and evidence schema version. Ordinary rule responses exclude raw coordinate history. A lower threshold might catch more fraud and falsely flag legitimate flights. Synthetic Cape Town/Johannesburg/Durban tests demonstrate the algorithm, not bank-grade accuracy. Multiple devices, shared/cloned cards and bad terminal metadata remain risks.

## R5 SMALL_PAYMENT_BURST_THEN_LARGE
If current amount <= large threshold, NO_MATCH. Otherwise count committed same-currency events for this customer with amount <= smallPaymentMaximum and occurredAt in (currentOccurredAt-window,currentOccurredAt), through contextHighWatermark. The current event and equal-event-time candidates are excluded; the upper boundary is strict because order at identical timestamps is unknown. MATCH when count >= minSmallPayments.

This uses event-time sequence, not arrival order. A delayed large payment must not treat later-occurring small payments as preceding it. Small payments delivered only after the large event will not retrospectively change the earlier evaluation. Evidence contains count/sum, window, threshold and watermark. The pattern is not a determination of card testing; failed authorization attempts are outside scope.

## Result algebra
V1 has no numerical severity or risk-score field: the decision is based on rule matches, without a weighted score or probability estimate. Named statuses are code-level contracts; rule thresholds/windows/enabled states are external policy.

Rule statuses: MATCH, NO_MATCH, NOT_APPLICABLE, INSUFFICIENT_DATA, DISABLED. DISABLED is excluded from coverage; NOT_APPLICABLE is an explicit successful applicability determination, not a missing fact.
- Any MATCH → FLAGGED, even if another enabled check lacks evidence.
- No MATCH and at least one INSUFFICIENT_DATA → INDETERMINATE.
- Otherwise → CLEAR (meaning no applicable evaluated rule matched; not proof of no fraud).
Coverage is PARTIAL iff any enabled rule is INSUFFICIENT_DATA; otherwise COMPLETE. Evidence sufficiency is not upstream delivery completeness. Unexpected rule bug → 500 and rollback; transient dependency/context failure → 503. Unexpected exceptions abort the evaluation transaction; no normal decision is saved.

| Enabled outcomes (other complete nonmatches omitted) | Decision | Coverage |
| --- | --- | --- |
| NO_MATCH | CLEAR | COMPLETE |
| NO_MATCH + NOT_APPLICABLE | CLEAR | COMPLETE |
| INSUFFICIENT_DATA, with no MATCH | INDETERMINATE | PARTIAL |
| MATCH | FLAGGED | COMPLETE |
| MATCH + INSUFFICIENT_DATA | FLAGGED | PARTIAL |
| Multiple MATCH | FLAGGED | COMPLETE unless another enabled rule lacks evidence |
| DISABLED + NO_MATCH | CLEAR | COMPLETE |

Disabled rules do not participate; all-disabled policy is invalid. A rule exception produces an operation error, never a normal partial evaluation. One pure aggregation function implements this table. New evaluations use the active policy; an identical old event returns its original result. A future severity model needs explicit meaning, combination/threshold/missing-data semantics, calibration evidence and API compatibility review.

## Rule extension
FraudRule exposes stable ID, algorithm version, typed configuration, bounded context requirements and evaluate(transaction,context). Dependency injection registers implementations without engine branching. A new rule consists of an implementation, typed configuration/validator and regression cases; new facts may also require context-provider/API/schema changes. Algorithm changes carry a new version. Extensibility acceptance is a test-only sixth rule registering without changes to engine orchestration or a closed five-rule API contract.
