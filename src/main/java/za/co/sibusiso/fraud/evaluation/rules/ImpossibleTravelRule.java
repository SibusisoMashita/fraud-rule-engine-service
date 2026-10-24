package za.co.sibusiso.fraud.evaluation.rules;

import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.domain.TransactionEvent.Channel;
import za.co.sibusiso.fraud.evaluation.history.LocationCandidates;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.policy.definition.BuiltInRuleSettings.ImpossibleTravel;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.RuleDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ImpossibleTravelRule implements FraudRule<ImpossibleTravel> {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final int EVIDENCE_DECIMAL_PLACES = 6;

    private final int candidateSafetyCeiling;
    private final RuleDefinition<ImpossibleTravel> definition = BuiltInRules.impossibleTravel();

    public ImpossibleTravelRule(int candidateSafetyCeiling) {
        ContractChecks.require(
                candidateSafetyCeiling > 0 && candidateSafetyCeiling < Integer.MAX_VALUE,
                "Invalid location safety ceiling");
        this.candidateSafetyCeiling = candidateSafetyCeiling;
    }

    @Override
    public RuleDefinition<ImpossibleTravel> definition() {
        return definition;
    }

    @Override
    public int maximumEvidenceFacts() {
        return 15; // Versioned response shape.
    }

    @Override
    public void validateConfiguration(ImpossibleTravel settings) {
        ContractChecks.require(
                settings.maxCandidates() <= candidateSafetyCeiling,
                "Location candidate limit exceeds infrastructure safety ceiling");
        for (double value :
                new double[] {
                    settings.maxSpeedKmh(),
                    settings.minDistanceKm(),
                    settings.travelAllowanceKm(),
                    settings.maxAccuracyKm()
                }) {
            ContractChecks.decimal(BigDecimal.valueOf(value));
        }
    }

    @Override
    public Optional<LocationQuery> locationQuery(
            TransactionEvent event, ImpossibleTravel settings) {
        return unavailable(event, settings).isPresent()
                ? Optional.empty()
                : Optional.of(query(event, settings));
    }

    private LocationQuery query(TransactionEvent event, ImpossibleTravel settings) {
        return new LocationQuery(
                event.instrumentId(),
                event.occurredAt(),
                settings.maxComparisonAgeSeconds(),
                settings.maxCandidates(),
                BigDecimal.valueOf(settings.maxAccuracyKm()),
                settings.trustedSources(),
                settings.eligibleChannels());
    }

    private Optional<String> unavailable(TransactionEvent event, ImpossibleTravel settings) {
        if (event.channel() == Channel.ECOMMERCE) {
            return Optional.of("ONLINE_TRANSACTION");
        }
        if (!settings.eligibleChannels().contains(event.channel())) {
            return Optional.of("CHANNEL_EXCLUDED_BY_POLICY");
        }
        if (event.physicalLocation() == null) {
            return Optional.of("MISSING_PHYSICAL_LOCATION");
        }
        if (!settings.trustedSources().contains(event.physicalLocation().source())) {
            return Optional.of("LOCATION_SOURCE_NOT_ELIGIBLE");
        }
        if (event.physicalLocation()
                        .accuracyKm()
                        .compareTo(BigDecimal.valueOf(settings.maxAccuracyKm()))
                > 0) {
            return Optional.of("LOCATION_TOO_UNCERTAIN");
        }
        return Optional.empty();
    }

    @Override
    public RuleResult evaluate(TransactionEvent event, ImpossibleTravel settings, Context context) {
        var unavailable = unavailable(event, settings);
        if (unavailable.isPresent()) {
            var status =
                    event.channel() == Channel.ECOMMERCE
                                    || !settings.eligibleChannels().contains(event.channel())
                            ? RuleResult.Status.NOT_APPLICABLE
                            : RuleResult.Status.INSUFFICIENT_DATA;
            return result(status, unavailable.orElseThrow(), evidence(settings, context, 0));
        }
        var candidates = context.history().required(query(event, settings));
        var evidence = evidence(settings, context, candidates.observations().size());
        if (candidates.overflow()) {
            return result(RuleResult.Status.INSUFFICIENT_DATA, "CANDIDATE_LIMIT", evidence);
        }
        if (candidates.observations().isEmpty()) {
            return result(RuleResult.Status.INSUFFICIENT_DATA, "NO_REFERENCE", evidence);
        }
        Pair strongestPair = null;
        for (var reference : candidates.observations()) {
            var pair = compare(event, reference, settings);
            if (pair.preferredTo(strongestPair)) {
                strongestPair = pair;
            }
        }
        var bestPair = Objects.requireNonNull(strongestPair);
        evidence.put("referenceEventId", new RuleResult.Text(bestPair.reference().eventId()));
        evidence.put("distanceKm", measured(bestPair.distanceKm()));
        evidence.put("effectiveDistanceKm", measured(bestPair.effectiveDistanceKm()));
        evidence.put("elapsedSeconds", measured(bestPair.elapsedSeconds()));
        evidence.put(
                "currentAccuracyKm", new RuleResult.Number(event.physicalLocation().accuracyKm()));
        evidence.put(
                "referenceAccuracyKm",
                new RuleResult.Number(bestPair.reference().location().accuracyKm()));
        return result(
                bestPair.match() ? RuleResult.Status.MATCH : RuleResult.Status.NO_MATCH,
                bestPair.match() ? "IMPOSSIBLE_TRAVEL_DETECTED" : "TRAVEL_WITHIN_LIMITS",
                evidence);
    }

    private Pair compare(
            TransactionEvent event,
            LocationCandidates.Observation reference,
            ImpossibleTravel settings) {
        var currentLocation = event.physicalLocation();
        var referenceLocation = reference.location();
        double distanceKm =
                GreatCircleDistance.kilometres(
                        currentLocation.latitude().doubleValue(),
                                currentLocation.longitude().doubleValue(),
                        referenceLocation.latitude().doubleValue(),
                                referenceLocation.longitude().doubleValue());
        double effectiveDistanceKm =
                StrictMath.max(
                        0,
                        distanceKm
                                - currentLocation.accuracyKm().doubleValue()
                                - referenceLocation.accuracyKm().doubleValue());
        var elapsed = Duration.between(event.occurredAt(), reference.occurredAt()).abs();
        double elapsedSeconds = elapsed.getSeconds() + elapsed.getNano() / NANOS_PER_SECOND;
        double allowedDistanceKm =
                settings.maxSpeedKmh() * (elapsedSeconds / SECONDS_PER_HOUR)
                        + settings.travelAllowanceKm();
        return new Pair(
                reference,
                distanceKm,
                effectiveDistanceKm,
                elapsedSeconds,
                effectiveDistanceKm - allowedDistanceKm,
                effectiveDistanceKm > settings.minDistanceKm()
                        && effectiveDistanceKm > allowedDistanceKm);
    }

    private LinkedHashMap<String, RuleResult.Fact> evidence(
            ImpossibleTravel settings, Context context, int count) {
        var facts = new LinkedHashMap<String, RuleResult.Fact>();
        facts.put(RuleResult.EVIDENCE_SCHEMA_VERSION_KEY, new RuleResult.Number(BigDecimal.ONE));
        facts.put("candidateCount", new RuleResult.Number(BigDecimal.valueOf(count)));
        facts.put(
                "contextHighWatermark",
                new RuleResult.Number(BigDecimal.valueOf(context.highWatermark())));
        facts.put("maxSpeedKmh", new RuleResult.Number(BigDecimal.valueOf(settings.maxSpeedKmh())));
        facts.put(
                "minDistanceKm",
                new RuleResult.Number(BigDecimal.valueOf(settings.minDistanceKm())));
        facts.put(
                "travelAllowanceKm",
                new RuleResult.Number(BigDecimal.valueOf(settings.travelAllowanceKm())));
        facts.put(
                "maxAccuracyKm",
                new RuleResult.Number(BigDecimal.valueOf(settings.maxAccuracyKm())));
        facts.put(
                "maxComparisonAgeSeconds",
                new RuleResult.Number(BigDecimal.valueOf(settings.maxComparisonAgeSeconds())));
        facts.put(
                "maxCandidates",
                new RuleResult.Number(BigDecimal.valueOf(settings.maxCandidates())));
        return facts;
    }

    private RuleResult result(
            RuleResult.Status status, String reasonCode, Map<String, RuleResult.Fact> evidence) {
        return new RuleResult(
                definition.id(), definition.algorithmVersion(), status, reasonCode, evidence);
    }

    /** Round measured evidence only; decisions always use unrounded values. */
    private RuleResult.Number measured(double value) {
        return new RuleResult.Number(
                BigDecimal.valueOf(value)
                        .setScale(EVIDENCE_DECIMAL_PLACES, RoundingMode.HALF_UP)
                        .stripTrailingZeros());
    }

    private record Pair(
            LocationCandidates.Observation reference,
            double distanceKm,
            double effectiveDistanceKm,
            double elapsedSeconds,
            double excessDistanceKm,
            boolean match) {
        boolean preferredTo(Pair other) {
            if (other == null) {
                return true;
            }
            if (match != other.match) {
                return match;
            }
            int order = Double.compare(excessDistanceKm, other.excessDistanceKm);
            return order > 0
                    || order == 0 && reference.eventId().compareTo(other.reference.eventId()) < 0;
        }
    }
}
