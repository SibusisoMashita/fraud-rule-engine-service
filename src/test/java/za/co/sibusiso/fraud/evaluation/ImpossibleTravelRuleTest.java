package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.ImpossibleTravel;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.domain.TransactionEvent.*;

import static org.assertj.core.api.Assertions.*;

class ImpossibleTravelRuleTest {
    private final StrictYaml yaml=new StrictYaml();
    private final ServiceLimits limits=yaml.read(new ClassPathResource("config/service.example.yaml"),ServiceLimits.class);
    private final ImpossibleTravel settings=(ImpossibleTravel)new PolicyLoader(yaml,RuleRegistry.of(BuiltInRules.definitions(),limits.api().maxRegisteredRules()))
            .read(yaml.readText(new ClassPathResource("config/policy.example.yaml"))
            ).rules().get(new RuleId("IMPOSSIBLE_TRAVEL")).settings();
    private final ImpossibleTravelRule rule=new ImpossibleTravelRule(limits.api().maxLocationCandidatesPerEvaluation());
    private final Instant now=Instant.parse("2026-10-15T12:00:00Z");
    private PhysicalLocation point(String lat,String lon,String accuracy,String city){return new PhysicalLocation(new BigDecimal(lat),new BigDecimal(lon),new BigDecimal(accuracy),LocationSource.VERIFIED_CARD_PRESENT_TERMINAL,city);}
    private TransactionEvent event(Instant time,PhysicalLocation location){return new TransactionEvent("current","customer","card",Type.CARD_PURCHASE,Status.BOOKED,Channel.CARD_PRESENT,Money.parse("ZAR","1.00"),"GROCERIES",time,location);}
    private LocationCandidates.Observation reference(String id,Instant time,PhysicalLocation location){return new LocationCandidates.Observation(id,time,location);}
    private PhysicalLocation capeTown(){return point("-33.9249","18.4241","0.1","Cape Town");}
    private PhysicalLocation johannesburg(){return point("-26.2041","28.0473","0.1","Johannesburg");}
    private PhysicalLocation durban(){return point("-29.8587","31.0218","0.1","Durban");}
    private RuleResult evaluate(TransactionEvent event,ImpossibleTravel policy,boolean overflow,LocationCandidates.Observation... previous) {
        var query=rule.locationQuery(event,policy).orElseThrow();
        return rule.evaluate(event,policy,new FraudRule.Context(now,9,new HistoryFacts(Map.of(),Map.of(query,new LocationCandidates(List.of(previous),overflow)))));
    }
    // Independent inverse-geodesic fixtures: GeographicLib 2.1, sphere radius 6371008.8m, flattening 0.
    @ParameterizedTest @CsvSource({"-33.9249,18.4241,-26.2041,28.0473,1261.5773177982558",
            "-26.2041,28.0473,-29.8587,31.0218,500.31619047771756",
            "-33.9249,18.4241,-29.8587,31.0218,1271.2588542068402",
            "0,179.9,0,-179.9,22.239016046705316","0,0,0,180,20015.114442035923",
            "89.999,0,89.999,180,0.22239016046812773","-33.9249,18.4241,-33.9249,18.4241,0"})
    void distanceMatchesIndependentSphericalReferences(double a,double b,double c,double d,double expected) {
        assertThat(GreatCircleDistance.kilometres(a,b,c,d)).isCloseTo(expected,within(0.000001));
        assertThat(GreatCircleDistance.kilometres(c,d,a,b)).isCloseTo(expected,within(0.000001));
    }
    @ParameterizedTest @CsvSource({"-33.9249,18.4241,-26.2041,28.0473,1261.1194416748694",
            "-26.2041,28.0473,-29.8587,31.0218,499.52149082014694",
            "-33.9249,18.4241,-29.8587,31.0218,1273.0945532747266"})
    void cityApproximationStaysWithinOnePercentOfIndependentWgs84References(double a,double b,double c,double d,double ellipsoidalKm) {
        assertThat(Math.abs(GreatCircleDistance.kilometres(a,b,c,d)-ellipsoidalKm)/ellipsoidalKm).isLessThan(0.01);
    }
    @ParameterizedTest @CsvSource({"0,MATCH","600,MATCH","10800,NO_MATCH","-600,MATCH"})
    void capeTownToJohannesburgUsesAbsoluteEventTimeIncludingEqualAndLaterNeighbors(long seconds,RuleResult.Status expected) {
        var result=evaluate(event(now,capeTown()),settings,false,reference("jhb",now.plusSeconds(seconds),johannesburg()));
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.evidence()).doesNotContainKeys("latitude","longitude","city");
        assertThat(result.evidence()).hasSize(rule.maximumEvidenceFacts());
    }
    @Test void examinesAllPairsAndChoosesStrongestViolationWithStableIdTieBreak() {
        var input=event(now,capeTown());var nearby=reference("nearby",now,capeTown());
        var a=reference("a",now,johannesburg());var z=reference("z",now,johannesburg());
        var result=evaluate(input,settings,false,nearby,z,a);
        assertThat(result.status()).isEqualTo(RuleResult.Status.MATCH);
        assertThat(((RuleResult.Text)result.evidence().get("referenceEventId")).value()).isEqualTo("a");
        var strongest=evaluate(input,settings,false,a,reference("durban",now,durban()));
        assertThat(((RuleResult.Text)strongest.evidence().get("referenceEventId")).value()).isEqualTo("durban");
    }
    @Test void aNearbyNonmatchWithGreaterExcessCannotHideAMatchingPair() {
        var input=event(now,point("0","0","0","Current"));
        var policy=policy(1000,50,25,5,500);
        // About 44 km at the same time has greater excess, but falls below the 50 km minimum.
        var nearby=reference("nearby",now,point("0","0.4","0","Nearby"));
        // About 111 km in five minutes exceeds both configured travel bounds.
        var matching=reference("matching",now.minusSeconds(300),point("0","1","0","Distant"));
        var result=evaluate(input,policy,false,nearby,matching);
        assertThat(result.status()).isEqualTo(RuleResult.Status.MATCH);
        assertThat(((RuleResult.Text)result.evidence().get("referenceEventId")).value()).isEqualTo("matching");
    }
    @Test void cityLabelsCannotCreateAnAnomalyAndAccuracyCirclesCanOverlap() {
        var input=event(now,capeTown());var samePlace=point("-33.9249","18.4241","0.1","Johannesburg");
        assertThat(evaluate(input,settings,false,reference("different-label",now,samePlace)).status()).isEqualTo(RuleResult.Status.NO_MATCH);
        var uncertain=policy(settings.maxSpeedKmh(),settings.minDistanceKm(),settings.travelAllowanceKm(),100,settings.maxCandidates());
        assertThat(evaluate(event(now,point("0","0","100","A")),uncertain,false,reference("overlap",now,point("0","1","100","B"))).status()).isEqualTo(RuleResult.Status.NO_MATCH);
    }
    @Test void equalityAtSpeedDistanceAndAllowanceBoundariesIsNotAMatch() {
        var input=event(now,point("0","0","0","A"));var previous=reference("one-degree",now.minusSeconds(3600),point("0","1","0","B"));
        double distance=GreatCircleDistance.kilometres(0,0,0,1);
        assertThat(evaluate(input,policy(distance,0,0,5,500),false,previous).status()).isEqualTo(RuleResult.Status.NO_MATCH);
        assertThat(evaluate(input,policy(Math.nextDown(distance),0,0,5,500),false,previous).status()).isEqualTo(RuleResult.Status.MATCH);
        var equalTime=reference("same-time",now,previous.location());
        assertThat(evaluate(input,policy(1,distance,0,5,500),false,equalTime).status()).isEqualTo(RuleResult.Status.NO_MATCH);
        assertThat(evaluate(input,policy(1,Math.nextDown(distance),0,5,500),false,equalTime).status()).isEqualTo(RuleResult.Status.MATCH);
        assertThat(evaluate(input,policy(1,0,distance,5,500),false,equalTime).status()).isEqualTo(RuleResult.Status.NO_MATCH);
    }
    @Test void distinguishesMissingReferenceOverflowAndMissingFacts() {
        var input=event(now,capeTown());
        assertThat(evaluate(input,settings,false).reasonCode()).isEqualTo("NO_REFERENCE");
        assertThat(evaluate(input,settings,true,reference("violation",now,johannesburg())).reasonCode()).isEqualTo("CANDIDATE_LIMIT");
        assertThat(evaluate(input,settings,true,reference("violation",now,johannesburg())).status()).isEqualTo(RuleResult.Status.INSUFFICIENT_DATA);
        assertThatThrownBy(()->rule.evaluate(input,settings,new FraudRule.Context(now,0))).hasMessageContaining("not loaded");
    }
    @Test void applicabilityAndCurrentAccuracyAvoidUnnecessaryQueries() {
        var online=new TransactionEvent("online","customer","card",Type.CARD_PURCHASE,Status.BOOKED,Channel.ECOMMERCE,Money.parse("ZAR","1"),"GROCERIES",now,null);
        assertThat(rule.locationQuery(online,settings)).isEmpty();
        assertThat(rule.evaluate(online,settings,new FraudRule.Context(now,0)).status()).isEqualTo(RuleResult.Status.NOT_APPLICABLE);
        var missing=event(now,null);
        assertThat(rule.locationQuery(missing,settings)).isEmpty();
        assertThat(rule.evaluate(missing,settings,new FraudRule.Context(now,0)).reasonCode()).isEqualTo("MISSING_PHYSICAL_LOCATION");
        var over=event(now,point("0","0","5.000001","A"));
        assertThat(rule.locationQuery(over,settings)).isEmpty();
        assertThat(rule.evaluate(over,settings,new FraudRule.Context(now,0)).reasonCode()).isEqualTo("LOCATION_TOO_UNCERTAIN");
        assertThat(rule.locationQuery(event(now,point("0","0","5","A")),settings)).isPresent();
    }
    @Test void validatesCandidateSafetyAndPolicyEvidenceBeforeActivation() {
        assertThatThrownBy(()->new ImpossibleTravelRule(10).validateConfiguration(settings)).hasMessageContaining("safety ceiling");
        assertThatThrownBy(()->rule.validateConfiguration(policy(1e100,0,0,5,500))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->rule.validateConfiguration(settings)).doesNotThrowAnyException();
    }
    private ImpossibleTravel policy(double speed,double minimum,double allowance,double accuracy,int cap) {
        return new ImpossibleTravel(true,settings.maxComparisonAgeSeconds(),cap,speed,minimum,allowance,accuracy,settings.trustedSources(),settings.eligibleChannels());
    }
}
