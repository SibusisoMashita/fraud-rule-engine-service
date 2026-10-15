package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.ImpossibleTravel;
import za.co.sibusiso.fraud.configuration.BuiltInRules;
import za.co.sibusiso.fraud.configuration.RuleDefinition;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.domain.TransactionEvent.Channel;

public final class ImpossibleTravelRule implements FraudRule<ImpossibleTravel> {
    private final int candidateSafetyCeiling;
    private final RuleDefinition<ImpossibleTravel> definition = BuiltInRules.impossibleTravel();
    public ImpossibleTravelRule(int candidateSafetyCeiling) {
        ContractChecks.require(candidateSafetyCeiling > 0 && candidateSafetyCeiling < Integer.MAX_VALUE, "Invalid location safety ceiling");
        this.candidateSafetyCeiling = candidateSafetyCeiling;
    }
    @Override public RuleDefinition<ImpossibleTravel> definition() { return definition; }
    @Override public int maximumEvidenceFacts() { return 15; } // Versioned response shape.
    @Override public void validateConfiguration(ImpossibleTravel settings) {
        ContractChecks.require(settings.maxCandidates() <= candidateSafetyCeiling, "Location candidate limit exceeds infrastructure safety ceiling");
        for (double value : new double[]{settings.maxSpeedKmh(), settings.minDistanceKm(), settings.travelAllowanceKm(), settings.maxAccuracyKm()})
            ContractChecks.decimal(BigDecimal.valueOf(value));
    }
    @Override public Optional<LocationQuery> locationQuery(TransactionEvent event, ImpossibleTravel settings) {
        return unavailable(event, settings).isPresent() ? Optional.empty() : Optional.of(query(event, settings));
    }
    private LocationQuery query(TransactionEvent event, ImpossibleTravel settings) {
        return new LocationQuery(event.instrumentId(), event.occurredAt(), settings.maxComparisonAgeSeconds(), settings.maxCandidates(),
                BigDecimal.valueOf(settings.maxAccuracyKm()), settings.trustedSources(), settings.eligibleChannels());
    }
    private Optional<String> unavailable(TransactionEvent event, ImpossibleTravel settings) {
        if (event.channel() == Channel.ECOMMERCE) return Optional.of("ONLINE_TRANSACTION");
        if (!settings.eligibleChannels().contains(event.channel())) return Optional.of("CHANNEL_EXCLUDED_BY_POLICY");
        if (event.physicalLocation() == null) return Optional.of("MISSING_PHYSICAL_LOCATION");
        if (!settings.trustedSources().contains(event.physicalLocation().source())) return Optional.of("LOCATION_SOURCE_NOT_ELIGIBLE");
        if (event.physicalLocation().accuracyKm().compareTo(BigDecimal.valueOf(settings.maxAccuracyKm())) > 0)
            return Optional.of("LOCATION_TOO_UNCERTAIN");
        return Optional.empty();
    }
    @Override public RuleResult evaluate(TransactionEvent event, ImpossibleTravel settings, Context context) {
        var unavailable = unavailable(event, settings);
        if (unavailable.isPresent()) {
            var status = event.channel() == Channel.ECOMMERCE || !settings.eligibleChannels().contains(event.channel())
                    ? RuleResult.Status.NOT_APPLICABLE : RuleResult.Status.INSUFFICIENT_DATA;
            return result(status, unavailable.orElseThrow(), evidence(settings, context, 0));
        }
        var candidates = context.history().required(query(event, settings));
        var evidence = evidence(settings, context, candidates.observations().size());
        if (candidates.overflow()) return result(RuleResult.Status.INSUFFICIENT_DATA, "CANDIDATE_LIMIT", evidence);
        if (candidates.observations().isEmpty()) return result(RuleResult.Status.INSUFFICIENT_DATA, "NO_REFERENCE", evidence);
        Pair strongest = null;
        for (var reference : candidates.observations()) {
            var pair = compare(event, reference, settings);
            if (pair.preferredTo(strongest)) strongest = pair;
        }
        var best = java.util.Objects.requireNonNull(strongest);
        evidence.put("referenceEventId", new RuleResult.Text(best.reference().eventId()));
        evidence.put("distanceKm", measured(best.distance()));
        evidence.put("effectiveDistanceKm", measured(best.effectiveDistance()));
        evidence.put("elapsedSeconds", measured(best.elapsedSeconds()));
        evidence.put("currentAccuracyKm", new RuleResult.Number(event.physicalLocation().accuracyKm()));
        evidence.put("referenceAccuracyKm", new RuleResult.Number(best.reference().location().accuracyKm()));
        return result(best.match() ? RuleResult.Status.MATCH : RuleResult.Status.NO_MATCH,
                best.match() ? "IMPOSSIBLE_TRAVEL_DETECTED" : "TRAVEL_WITHIN_LIMITS", evidence);
    }
    private Pair compare(TransactionEvent event, LocationCandidates.Observation reference, ImpossibleTravel settings) {
        var here = event.physicalLocation(); var there = reference.location();
        double distance = GreatCircleDistance.kilometres(here.latitude().doubleValue(), here.longitude().doubleValue(),
                there.latitude().doubleValue(), there.longitude().doubleValue());
        double effective = StrictMath.max(0, distance - here.accuracyKm().doubleValue() - there.accuracyKm().doubleValue());
        var elapsed = Duration.between(event.occurredAt(), reference.occurredAt()).abs();
        double seconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
        double allowed = settings.maxSpeedKmh() * (seconds / 3600.0) + settings.travelAllowanceKm();
        return new Pair(reference, distance, effective, seconds, effective - allowed,
                effective > settings.minDistanceKm() && effective > allowed);
    }
    private LinkedHashMap<String, RuleResult.Fact> evidence(ImpossibleTravel settings, Context context, int count) {
        var facts = new LinkedHashMap<String, RuleResult.Fact>();
        facts.put("evidenceSchemaVersion", new RuleResult.Number(BigDecimal.ONE));
        facts.put("candidateCount", new RuleResult.Number(BigDecimal.valueOf(count)));
        facts.put("contextHighWatermark", new RuleResult.Number(BigDecimal.valueOf(context.highWatermark())));
        facts.put("maxSpeedKmh", new RuleResult.Number(BigDecimal.valueOf(settings.maxSpeedKmh())));
        facts.put("minDistanceKm", new RuleResult.Number(BigDecimal.valueOf(settings.minDistanceKm())));
        facts.put("travelAllowanceKm", new RuleResult.Number(BigDecimal.valueOf(settings.travelAllowanceKm())));
        facts.put("maxAccuracyKm", new RuleResult.Number(BigDecimal.valueOf(settings.maxAccuracyKm())));
        facts.put("maxComparisonAgeSeconds", new RuleResult.Number(BigDecimal.valueOf(settings.maxComparisonAgeSeconds())));
        facts.put("maxCandidates", new RuleResult.Number(BigDecimal.valueOf(settings.maxCandidates())));
        return facts;
    }
    private RuleResult result(RuleResult.Status status, String reason, Map<String, RuleResult.Fact> evidence) {
        return new RuleResult(definition.id(), definition.algorithmVersion(), status, reason, evidence);
    }
    /** Six decimal places in measured evidence only; decisions always use unrounded values. */
    private RuleResult.Number measured(double value) {
        return new RuleResult.Number(BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros());
    }
    private record Pair(LocationCandidates.Observation reference, double distance, double effectiveDistance,
            double elapsedSeconds, double excess, boolean match) {
        boolean preferredTo(Pair other) {
            if (other == null) return true;
            if (match != other.match) return match;
            int order = Double.compare(excess, other.excess);
            return order > 0 || order == 0 && reference.eventId().compareTo(other.reference.eventId()) < 0;
        }
    }
}
