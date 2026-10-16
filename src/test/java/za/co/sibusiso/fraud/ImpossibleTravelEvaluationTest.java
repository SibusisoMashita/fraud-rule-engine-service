package za.co.sibusiso.fraud;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.evaluation.*;
import za.co.sibusiso.fraud.ingress.CredentialAuthenticator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImpossibleTravelEvaluationTest {
    private static final String PASSWORD=TestDatabase.PASSWORD,SECRET="d".repeat(64);
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer(DockerImageName.parse(
            TestImages.POSTGRES).asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse(
            TestImages.REDIS))
            .withExposedPorts(6379).withCommand("redis-server","--requirepass",PASSWORD);
    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry properties) throws Exception {
        TestDatabase.initialize(DATABASE);
        properties.add("fraud.cursor-signing-key",()->"a".repeat(64));
        properties.add("spring.datasource.url",DATABASE::getJdbcUrl);properties.add("spring.datasource.password",()->PASSWORD);
        properties.add("spring.data.redis.host",REDIS::getHost);properties.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password",()->PASSWORD);
    }
    @LocalServerPort int port;
    @Autowired ServiceLimits limits;
    @MockitoSpyBean JdbcTransactionStore store;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newHttpClient();
    private final String original=new StrictYaml().readText(new org.springframework.core.io.ClassPathResource("config/policy.example.yaml"));
    record Identity(String scope,String keyId) {String key(){return keyId+"."+SECRET;}}
    private JdbcTemplate database(String role){return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),role,PASSWORD));}
    private Identity identity(){
        String id=UUID.randomUUID().toString();var who=new Identity("history-http-"+id,id);
        var admin=database("fraud_security_admin");
        admin.update("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['INGEST','READ'],ARRAY['VERIFIED_ATM_TERMINAL','VERIFIED_CARD_PRESENT_TERMINAL']::text[])",id,who.scope());
        admin.update("INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')",id,id,HexFormat.of().formatHex(CredentialAuthenticator.digest(SECRET)));
        return who;
    }
    @AfterEach void restore(){reset(store);activate(original);}

    @Test void firstPhysicalEventIsIndeterminateAndImpossiblePairIsExplainableAndReplayable() throws Exception {
        var who=identity();Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        var first=post(who,physical("cape","card","Cape Town",now.minusSeconds(600),"0.1",false));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(json.readTree(first.body()).at("/evaluation/decision").asString()).isEqualTo("INDETERMINATE");
        assertThat(travel(first).get("reasonCode").asString()).isEqualTo("NO_REFERENCE");
        String input=physical("jhb","card","Johannesburg",now,"0.1",true);var second=post(who,input);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(json.readTree(second.body()).at("/evaluation/decision").asString()).isEqualTo("FLAGGED");
        assertThat(json.readTree(second.body()).at("/evaluation/coverage").asString()).isEqualTo("COMPLETE");
        assertThat(travel(second).get("status").asString()).isEqualTo("MATCH");
        assertThat(travel(second).at("/evidence/referenceEventId").asString()).isEqualTo("cape");
        assertThat(travel(second).at("/evidence/candidateCount").asInt()).isEqualTo(1);
        assertThat(travel(second).get("evidence").has("latitude")).isFalse();
        assertThat(travel(second).get("evidence").has("longitude")).isFalse();
        var replay=post(who,input);assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.body()).isEqualTo(second.body());
        assertThat(get("/api/v1/transactions/jhb",who.key()).body()).isEqualTo(second.body());assertCounts(who,2);
    }
    @Test void plausibleFlightsDifferentCardsAndScopesDoNotCreateFalseTravelMatches() throws Exception {
        var who=identity();Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(post(who,physical("cape","card","Cape Town",now.minusSeconds(10800),"0.1",false)).statusCode()).isEqualTo(201);
        var flight=post(who,physical("flight","card","Johannesburg",now,"0.1",false));
        assertThat(travel(flight).get("status").asString()).isEqualTo("NO_MATCH");
        var differentCard=post(who,physical("other-card","other-card","Durban",now,"0.1",false));
        assertThat(travel(differentCard).get("reasonCode").asString()).isEqualTo("NO_REFERENCE");
        var differentScope=post(identity(),physical("other-scope","card","Durban",now,"0.1",false));
        assertThat(travel(differentScope).get("reasonCode").asString()).isEqualTo("NO_REFERENCE");
    }
    @Test void laterEventTimeNeighborsFromEarlierDeliveryAndEqualTimesAreCompared() throws Exception {
        var who=identity();Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(post(who,physical("later-occurrence","card","Johannesburg",now,"0.1",false)).statusCode()).isEqualTo(201);
        var delayed=post(who,physical("delayed","card","Cape Town",now.minusSeconds(600),"0.1",false));
        assertThat(travel(delayed).get("status").asString()).isEqualTo("MATCH");
        var equal=post(who,physical("equal","card","Durban",now,"0.1",false));
        assertThat(travel(equal).get("status").asString()).isEqualTo("MATCH");
        assertThat(travel(equal).at("/evidence/candidateCount").asInt()).isEqualTo(2);
    }
    @Test void businessSpeedDistanceAllowanceAgeAccuracyChannelsAndToggleChangeWithoutRestart() throws Exception {
        var who=identity();Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(post(who,physical("cape","card","Cape Town",now.minusSeconds(600),"0.1",false)).statusCode()).isEqualTo(201);
        String originalInput=physical("original","card","Johannesburg",now,"0.1",false);var originalResult=post(who,originalInput);
        assertThat(travel(originalResult).get("status").asString()).isEqualTo("MATCH");
        for (var change:java.util.Map.of("speed",original.replace("maxSpeedKmh: 1000","maxSpeedKmh: 20000"),
                "distance",original.replace("minDistanceKm: 50","minDistanceKm: 2000"),
                "allowance",original.replace("travelAllowanceKm: 25","travelAllowanceKm: 2000"),
                "age",original.replace("maxComparisonAgeSeconds: 86400","maxComparisonAgeSeconds: 60")).entrySet()) {
            String hash=activate(change.getValue());var response=post(who,physical(change.getKey(),"card","Johannesburg",now,"0.1",false));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(travel(response).get("status").asString()).isEqualTo("NO_MATCH");
            assertThat(json.readTree(response.body()).at("/evaluation/policyHash").asString()).isEqualTo(hash);
        }
        activate(original.replace("maxAccuracyKm: 5","maxAccuracyKm: 0"));clearInvocations(store);
        var uncertain=post(who,physical("accuracy","card","Johannesburg",now,"0.1",false));
        assertThat(travel(uncertain).get("reasonCode").asString()).isEqualTo("LOCATION_TOO_UNCERTAIN");
        verify(store,never()).locations(anyString(),anyString(),any(),anyLong());
        activate(original.replace("trustedSources: [VERIFIED_ATM_TERMINAL, VERIFIED_CARD_PRESENT_TERMINAL]","trustedSources: [VERIFIED_ATM_TERMINAL]")
                .replace("eligibleChannels: [ATM, CARD_PRESENT]","eligibleChannels: [ATM]"));
        assertThat(travel(post(who,physical("channel","card","Johannesburg",now,"0.1",false))).get("status").asString()).isEqualTo("NOT_APPLICABLE");
        activate(original.replace("impossibleTravel:\n    enabled: true","impossibleTravel:\n    enabled: false"));clearInvocations(store);
        assertThat(travel(post(who,physical("disabled","card","Johannesburg",now,"0.1",false))).get("status").asString()).isEqualTo("DISABLED");
        verify(store,never()).locations(anyString(),anyString(),any(),anyLong());
        activate(original.replace("maxCandidates: 500","maxCandidates: 1"));
        assertThat(travel(post(who,physical("overflow","card","Johannesburg",now,"0.1",false))).get("reasonCode").asString()).isEqualTo("CANDIDATE_LIMIT");
        activate(original);var replay=post(who,originalInput);assertThat(replay.statusCode()).isEqualTo(200);assertThat(replay.body()).isEqualTo(originalResult.body());
    }
    @Test void unauthorizedSourceAndCandidateSafetyCeilingCannotBeBypassed() throws Exception {
        var who=identity();
        database("fraud_security_admin").update("UPDATE fraud.api_principals SET location_sources=ARRAY[]::text[] WHERE scope=?",who.scope());
        var response=post(who,physical("forbidden","card","Cape Town",Instant.now(),"0.1",false));
        assertThat(response.statusCode()).isEqualTo(403);assertCounts(who,0);
        String hash=database("fraud_runtime").queryForObject("SELECT policy_hash FROM fraud.policy_head WHERE singleton",String.class);
        assertThatThrownBy(()->activate(original.replace("maxCandidates: 500","maxCandidates: "+(limits.api().maxLocationCandidatesPerEvaluation()+1))))
                .hasMessageContaining("safety ceiling");
        assertThat(database("fraud_runtime").queryForObject("SELECT policy_hash FROM fraud.policy_head WHERE singleton",String.class)).isEqualTo(hash);
    }
    @Test void locationQueryFailureRollsBackAndRetryUsesOnlyCommittedHistory() throws Exception {
        var who=identity();Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(post(who,physical("reference","card","Cape Town",now.minusSeconds(600),"0.1",false)).statusCode()).isEqualTo(201);
        String input=physical("retry","card","Johannesburg",now,"0.1",false);
        doThrow(new QueryTimeoutException("Synthetic location query timeout")).when(store).locations(anyString(),anyString(),any(),anyLong());
        var failed=post(who,input);assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);assertCounts(who,1);
        assertThat(json.readTree(failed.body()).get("outcome").asString()).isEqualTo("NOT_ACCEPTED");
        reset(store);var retry=post(who,input);assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
        assertThat(travel(retry).at("/evidence/candidateCount").asInt()).isEqualTo(1);assertCounts(who,2);
    }
    @Test void policyActivationDuringLocationLookupKeepsTheCapturedLimitsAndHash() throws Exception {
        var who=identity();Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(post(who,physical("reference","card","Cape Town",now.minusSeconds(600),"0.1",false)).statusCode()).isEqualTo(201);
        String old=database("fraud_runtime").queryForObject("SELECT policy_hash FROM fraud.policy_head WHERE singleton",String.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        doAnswer(invocation->{if(once.compareAndSet(false,true)){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test release timed out");}return invocation.callRealMethod();})
                .when(store).locations(anyString(),anyString(),any(),anyLong());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->post(who,physical("captured","card","Johannesburg",now,"0.1",false)));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();String changed=activate(original.replace("maxSpeedKmh: 1000","maxSpeedKmh: 20000"));release.countDown();
                var response=pending.get(12,TimeUnit.SECONDS);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                assertThat(travel(response).get("status").asString()).isEqualTo("MATCH");
                assertThat(json.readTree(response.body()).at("/evaluation/policyHash").asString()).isEqualTo(old);
                var after=post(who,physical("after","card","Johannesburg",now,"0.1",false));
                assertThat(travel(after).get("status").asString()).isEqualTo("NO_MATCH");
                assertThat(json.readTree(after.body()).at("/evaluation/policyHash").asString()).isEqualTo(changed);
            }finally{release.countDown();}
        }
    }
    @Test void swaggerServesTheMaintainedContractAndProtectedApiStillNeedsAKey() throws Exception {
        var page=get("/swagger-ui/index.html",null);assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains("swagger-ui-bundle.js");
        assertThat(get("/swagger-ui/assets/swagger-ui-bundle.js",null).statusCode()).isEqualTo(200);
        assertThat(get("/swagger-ui/assets/swagger-ui.css",null).statusCode()).isEqualTo(200);
        var initializer=get("/swagger-ui/swagger-initializer.js",null);
        assertThat(initializer.body()).contains("validatorUrl: null","queryConfigEnabled: false","persistAuthorization: false");
        var specification=get("/openapi.yaml",null);
        assertThat(specification.statusCode()).isEqualTo(200);
        assertThat(specification.body()).isEqualTo(new StrictYaml().readText(new org.springframework.core.io.ClassPathResource("api/openapi.yaml")));
        assertThat(specification.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(get("/api/v1/transactions/example",null).statusCode()).isEqualTo(401);
        assertThat(get("/webjars/swagger-ui/index.html",null).statusCode()).isEqualTo(403);
    }
    private String physical(String id,String card,String city,Instant occurred,String accuracy,boolean atm) {
        var input=(tools.jackson.databind.node.ObjectNode)json.readTree(body(id,"customer",card,"1.00",occurred));
        input.put("transactionType",atm?"ATM_WITHDRAWAL":"CARD_PURCHASE");input.put("channel",atm?"ATM":"CARD_PRESENT");
        var coordinates=switch(city){case "Cape Town"->List.of("-33.9249","18.4241");case "Johannesburg"->List.of("-26.2041","28.0473");default->List.of("-29.8587","31.0218");};
        input.set("physicalLocation",json.valueToTree(Map.of("latitude",new java.math.BigDecimal(coordinates.get(0)),"longitude",new java.math.BigDecimal(coordinates.get(1)),
                "accuracyKm",new java.math.BigDecimal(accuracy),"city",city,"source",atm?"VERIFIED_ATM_TERMINAL":"VERIFIED_CARD_PRESENT_TERMINAL")));
        return json.writeValueAsString(input);
    }
    private JsonNode travel(HttpResponse<String> response){return result(json.readTree(response.body()).get("evaluation"),"IMPOSSIBLE_TRAVEL");}
    private HttpResponse<String> get(String path,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));
        if(key!=null)request.header("X-API-Key",key);
        return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private String activate(String source) {
        var snapshot=new PolicyLoader(new StrictYaml(),RuleRegistry.of(BuiltInRules.definitions(),limits.api().maxRegisteredRules())).read(source);
        new RuleEngine(List.of(new HighAmountRule(),new TransactionVelocityRule(),new CumulativeSpendRule(),new SmallPaymentBurstRule(),new ImpossibleTravelRule(limits.api().maxLocationCandidatesPerEvaluation())),limits.api().maxEvidenceFactsPerRule()).verify(snapshot);
        return database("fraud_policy_admin").queryForObject("SELECT fraud.activate_policy(?,?,false)",String.class,snapshot.policyHash(),source);
    }
    private JsonNode result(JsonNode evaluation,String id){for(var r:evaluation.get("ruleResults"))if(r.get("ruleId").asString().equals(id))return r;throw new AssertionError("Missing "+id);}
    private void assertCounts(Identity who,int events){for(String table:List.of("transactions","evaluations"))assertThat(database("fraud_runtime").queryForObject("SELECT count(*) FROM fraud."+table+" WHERE scope=?",Integer.class,who.scope())).isEqualTo(events);
        assertThat(database("fraud_runtime").queryForObject("SELECT count(*) FROM fraud.rule_results WHERE scope=?",Integer.class,who.scope())).isEqualTo(events*5);}
    private String body(String event,String customer,String card,String amount,Instant occurredAt) {
        return json.writeValueAsString(Map.of("eventId",event,"customerId",customer,"instrumentId",card,"transactionType","CARD_PURCHASE","status","BOOKED","channel","ECOMMERCE","amount",amount,"currency","ZAR","category","GROCERIES","occurredAt",occurredAt.truncatedTo(ChronoUnit.MICROS).toString()));
    }
    private HttpResponse<String> post(Identity who,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/transactions"))
                .timeout(Duration.ofMillis(limits.api().exampleClientDeadlineMillis())).header("X-API-Key",who.key()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
