package za.co.sibusiso.fraud;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.*;
import za.co.sibusiso.fraud.ingress.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionalEvaluationTest {
    private static final String PASSWORD=TestDatabase.PASSWORD;
    private static final String SECRET="c".repeat(64);
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse(
            "redis:8.2@sha256:47670742d7924adbcdb404d1288b6327815b23141969c0939b8e5d61f78c2634"))
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
    @Autowired JdbcTemplate jdbc;
    @Autowired EvaluationWriter writer;
    @Autowired TransactionService service;
    @Autowired CredentialAuthenticator credentials;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean HighAmountRule high;
    @MockitoSpyBean JdbcTransactionStore store;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String original=new StrictYaml().readText(new org.springframework.core.io.ClassPathResource("config/policy.example.yaml"));
    record Identity(String scope,String principal,String keyId) {String key(){return keyId+"."+SECRET;}}
    private JdbcTemplate database(String role) {return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),role,PASSWORD));}
    private JdbcTemplate observer(){return database("fraud_runtime");}
    private Identity identity(){return identity("scope-"+UUID.randomUUID());}
    private Identity identity(String scope){
        String id=UUID.randomUUID().toString(); var who=new Identity(scope,"producer-"+id,id);
        database("fraud_security_admin").update("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['INGEST','READ'],ARRAY['VERIFIED_ATM_TERMINAL','VERIFIED_CARD_PRESENT_TERMINAL'])",who.principal(),scope);
        database("fraud_security_admin").update("INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')",id,who.principal(),HexFormat.of().formatHex(CredentialAuthenticator.digest(SECRET)));
        return who;
    }
    @AfterEach void resetSpies(){reset(high,store);}

    @Test void commitsThenRetrievesAndReplaysTheOriginalCompleteResult() throws Exception {
        var who=identity();String input=body("purchase","customer","card","25000.01");
        var created=post(who,input);assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var resource=json.readTree(created.body());var evaluation=resource.get("evaluation");
        assertThat(evaluation.get("decision").asString()).isEqualTo("FLAGGED");
        assertThat(evaluation.get("coverage").asString()).isEqualTo("COMPLETE");
        assertThat(evaluation.get("buildId").asString()).matches("sha256:[a-f0-9]{64}");
        assertThat(evaluation.get("contextHighWatermark").asLong()).isZero();
        assertThat(evaluation.get("ruleResults").size()).isEqualTo(5);
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/transactions/purchase");
        assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(get(who,"purchase").body())).isEqualTo(resource);
        var replay=post(who,input.replace("25000.01","25000.0100"));
        assertThat(replay.statusCode()).isEqualTo(200);assertThat(json.readTree(replay.body())).isEqualTo(resource);
        var offsetInput=(tools.jackson.databind.node.ObjectNode)json.readTree(input);
        offsetInput.put("occurredAt",Instant.parse(offsetInput.get("occurredAt").asString()).atOffset(ZoneOffset.ofHours(2))
                .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX")));
        var offsetReplay=post(who,json.writeValueAsString(offsetInput));
        assertThat(offsetReplay.statusCode()).isEqualTo(200);assertThat(offsetReplay.body()).isEqualTo(created.body());
        assertThat(post(who,input.replace("25000.01","25000.02")).statusCode()).isEqualTo(409);
        assertCounts(who,1,1);assertThat(AopUtils.isAopProxy(writer)).isTrue();
        assertThat(observer().queryForObject("SELECT customer_sequence FROM fraud.customer_processing_state WHERE scope=?",Long.class,who.scope())).isEqualTo(1);
        assertThatThrownBy(()->observer().update("UPDATE fraud.transactions SET amount=1 WHERE scope=?",who.scope())).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(()->observer().update("DELETE FROM fraud.rule_results WHERE scope=?",who.scope())).rootCause().hasMessageContaining("permission denied");
    }
    @Test void livePolicyChangesAffectNewEventsAndNeverRewriteReplays() throws Exception {
        var who=identity();String first=body("first","customer","card","1.00");
        var before=post(who,first);assertThat(before.statusCode()).isEqualTo(201);
        try {
            String hash=activate(original.replace("25000.00","0.50"));
            var after=post(who,first.replace("first","second"));
            assertThat(after.statusCode()).as(after.body()).isEqualTo(201);
            assertThat(json.readTree(after.body()).at("/evaluation/decision").asString()).isEqualTo("FLAGGED");
            assertThat(json.readTree(after.body()).at("/evaluation/policyHash").asString()).isEqualTo(hash);
            assertThat(json.readTree(after.body()).at("/evaluation/contextHighWatermark").asLong()).isEqualTo(1);
            assertThat(post(who,first).body()).isEqualTo(before.body());
        } finally {activate(original);}
    }
    @Test void liveCurrencyPrecisionAndAmountLimitsApplyWithoutRestart() throws Exception {
        var who=identity();
        String precise=body("precise","customer","card","10.1234").replace("ZAR","USD");
        String above=precise.replace("precise","above").replace("10.1234","10.1235");
        try {
            activate(original.replace("ZAR","USD").replace("scale: 2","scale: 4").replace("25000.00","10.1234"));
            var equality=post(who,precise);
            assertThat(equality.statusCode()).as(equality.body()).isEqualTo(201);
            assertThat(json.readTree(equality.body()).at("/evaluation/decision").asString()).isEqualTo("CLEAR");
            var match=post(who,above);
            assertThat(match.statusCode()).as(match.body()).isEqualTo(201);
            assertThat(json.readTree(match.body()).at("/evaluation/decision").asString()).isEqualTo("FLAGGED");
            var highResult=json.readTree(match.body()).at("/evaluation/ruleResults").findValues("currency");
            assertThat(highResult).hasSize(3).allSatisfy(currency->assertThat(currency.asString()).isEqualTo("USD"));
            activate(original.replace("ZAR","USD").replace("999999999999.99","40000.00"));
            var scale=post(who,above.replace("above","excess-scale"));
            assertThat(scale.statusCode()).as(scale.body()).isEqualTo(422);
            assertThat(json.readTree(scale.body()).get("code").asString()).isEqualTo("AMOUNT_SCALE");
            var maximum=post(who,body("excess-amount","customer","card","40000.01").replace("ZAR","USD"));
            assertThat(maximum.statusCode()).as(maximum.body()).isEqualTo(422);
            assertThat(json.readTree(maximum.body()).get("code").asString()).isEqualTo("AMOUNT_LIMIT");
            var replay=post(who,above);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.body()).isEqualTo(match.body());
            assertCounts(who,2,1);
        } finally {activate(original);}
    }
    @Test void liveIngestionWindowsRejectNewEventsAndAllowRetryAfterPolicyChange() throws Exception {
        var who=identity();
        var past=(tools.jackson.databind.node.ObjectNode)json.readTree(body("past","customer","card","1.00"));
        past.put("occurredAt",Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS).toString());
        var future=(tools.jackson.databind.node.ObjectNode)json.readTree(body("future","customer","card","1.00"));
        future.put("occurredAt",Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS).toString());
        String pastInput=json.writeValueAsString(past),futureInput=json.writeValueAsString(future);
        try {
            activate(original.replace("maxEventAgeSeconds: 604800","maxEventAgeSeconds: 1")
                    .replace("maxFutureSkewSeconds: 120","maxFutureSkewSeconds: 0"));
            var old=post(who,pastInput);var ahead=post(who,futureInput);
            assertThat(old.statusCode()).as(old.body()).isEqualTo(422);
            assertThat(json.readTree(old.body()).get("code").asString()).isEqualTo("EVENT_TOO_OLD");
            assertThat(ahead.statusCode()).as(ahead.body()).isEqualTo(422);
            assertThat(json.readTree(ahead.body()).get("code").asString()).isEqualTo("EVENT_IN_FUTURE");
            assertCounts(who,0,0);
            activate(original);
            assertThat(post(who,pastInput).statusCode()).isEqualTo(201);
            assertThat(post(who,futureInput).statusCode()).isEqualTo(201);
            assertCounts(who,2,1);
        } finally {activate(original);}
    }
    @Test void replayPrecedesCurrentCurrencyAndAgeEligibility() throws Exception {
        var who=identity();String input=body("old","customer","card","1.00");
        var tree=json.readTree(input);var fields=new LinkedHashMap<String,Object>();tree.properties().forEach(e->fields.put(e.getKey(),e.getValue()));
        fields.put("occurredAt",Instant.now().minusSeconds(3).truncatedTo(ChronoUnit.MICROS).toString());input=json.writeValueAsString(fields);
        var first=post(who,input);assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        try {
            activate(original.replace("ZAR","USD").replace("maxEventAgeSeconds: 604800","maxEventAgeSeconds: 1"));
            assertThat(post(who,input).body()).isEqualTo(first.body());
            var rejected=post(who,input.replace("\"old\"","\"new\""));
            assertThat(rejected.statusCode()).isEqualTo(422);assertThat(json.readTree(rejected.body()).get("code").asString()).isEqualTo("UNSUPPORTED_CURRENCY");
            assertCounts(who,1,1);
        } finally {activate(original);}
    }
    @Test void scopeAndInstrumentOwnershipCannotBeChangedByPayloadOrHeaders() throws Exception {
        var first=identity();var second=identity();String input=body("shared-id","customer","card","1.00");
        assertThat(post(first,input).statusCode()).isEqualTo(201);
        assertThat(get(second,"shared-id").statusCode()).isEqualTo(404);
        assertThat(post(second,input.replace("1.00","2.00")).statusCode()).isEqualTo(201);
        assertThat(json.readTree(get(first,"shared-id").body()).at("/transaction/amount").asString()).isEqualTo("1");
        var changed=post(first,input.replace("shared-id","other-event").replace("\"customer\"","\"other-customer\""));
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(json.readTree(changed.body()).get("code").asString()).isEqualTo("INSTRUMENT_OWNER_CONFLICT");
        assertCounts(first,1,1);
        assertThat(observer().queryForObject("SELECT count(*) FROM fraud.customer_processing_state WHERE scope=?",Integer.class,first.scope())).isEqualTo(1);
    }
    @Test void checkedAndUncheckedRuleFailuresRollBackAllPartialWrites() throws Exception {
        for (Exception failure:List.of(new Exception("synthetic checked failure"),new IllegalStateException("synthetic rule failure"))) {
            var who=identity();String input=body("failed","customer","card","1.00");
            doThrow(failure).when(high).evaluate(any(),any(),any());
            var result=post(who,input);assertThat(result.statusCode()).as(result.body()).isEqualTo(500);
            assertThat(json.readTree(result.body()).get("outcome").asString()).isEqualTo("NOT_ACCEPTED");
            assertCounts(who,0,0);
            assertThat(observer().queryForObject("SELECT count(*) FROM fraud.customer_processing_state WHERE scope=?",Integer.class,who.scope())).isZero();
            reset(high);assertThat(post(who,input).statusCode()).isEqualTo(201);
        }
    }
    @Test void independentReadersCannotObserveUncommittedEventOrResults() throws Exception {
        var who=identity();
        doAnswer(invocation->{assertCounts(who,0,0);return invocation.callRealMethod();}).when(high).evaluate(any(),any(),any());
        assertThat(post(who,body("atomic","customer","card","1.00")).statusCode()).isEqualTo(201);
        assertCounts(who,1,1);
    }
    @Test void concurrentIdenticalRequestsHaveOneCommittedEffect() throws Exception {
        var who=identity();String input=body("same","customer","card","1.00");var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<8;i++)jobs.add(executor.submit(()->{start.await();return post(who,input);}));
            start.countDown();int created=0;Set<String>ids=new HashSet<>();
            for(var job:jobs){var response=job.get(12,TimeUnit.SECONDS);assertThat(response.statusCode()).as(response.body()).isIn(200,201);
                if(response.statusCode()==201)created++;ids.add(json.readTree(response.body()).at("/evaluation/id").asString());}
            assertThat(created).isEqualTo(1);assertThat(ids).hasSize(1);
        }
        assertCounts(who,1,1);
    }
    @Test void conflictingCustomerRaceRecoversOutsideTheAbortedTransaction() throws Exception {
        var who=identity();var barrier=new CyclicBarrier(2);var lookups=new AtomicInteger();
        doAnswer(invocation->{var found=invocation.callRealMethod();
            if(!TransactionSynchronizationManager.isActualTransactionActive() && lookups.incrementAndGet()<=2)barrier.await(5,TimeUnit.SECONDS);
            return found;
        }).when(store).find(who.scope(),"race");
        String input=body("race","first-customer","first-card","1.00");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->post(who,input));
            var second=executor.submit(()->post(who,input.replace("first-customer","second-customer").replace("first-card","second-card")));
            assertThat(List.of(first.get(12,TimeUnit.SECONDS).statusCode(),second.get(12,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201,409);
        }
        assertCounts(who,1,1);
        assertThat(observer().queryForObject("SELECT count(*) FROM fraud.customer_processing_state WHERE scope=?",Integer.class,who.scope())).isEqualTo(1);
        assertThat(lookups.get()).isGreaterThanOrEqualTo(3);
    }
    @Test void heldCustomerLockRejectsWithinBudgetAndAllowsSafeRetry() throws Exception {
        var who=identity();String first=body("first","customer","card","1.00");assertThat(post(who,first).statusCode()).isEqualTo(201);
        String next=first.replace("first","second");
        try(var connection=DriverManager.getConnection(DATABASE.getJdbcUrl(),"fraud_runtime",PASSWORD)) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT customer_sequence FROM fraud.customer_processing_state WHERE scope=? AND customer_id='customer' FOR UPDATE")) {
                lock.setString(1,who.scope());lock.executeQuery().close();
                long start=System.nanoTime();var denied=post(who,next);
                assertThat(denied.statusCode()).as(denied.body()).isEqualTo(503);
                assertThat(json.readTree(denied.body()).get("outcome").asString()).isEqualTo("NOT_ACCEPTED");
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(limits.api().businessDeadlineMillis());
                assertCounts(who,1,1);
            }finally{connection.rollback();}
        }
        assertThat(post(who,next).statusCode()).isEqualTo(201);assertCounts(who,2,1);
    }
    @Test void expirationAfterRuleExecutionRollsBackBeforeCommit() throws Exception {
        var who=identity();var remaining=new AtomicLong(limits.api().businessDeadlineMillis());
        doAnswer(invocation->{Object result=invocation.callRealMethod();remaining.set(limits.operations().commitReserveMillis());return result;})
                .when(high).evaluate(any(),any(),any());
        var caller=credentials.authenticate(who.key());var event=decode(body("budget","customer","card","1.00"));
        assertThatThrownBy(()->service.submit(caller,event,remaining::get)).isInstanceOfSatisfying(FlowFailure.class,e->assertThat(e.code()).isEqualTo("DEADLINE_EXCEEDED"));
        assertCounts(who,0,0);
    }
    @Test void aLostCommitAcknowledgementReturnsUnknownAndReplayFindsTheCommittedResult() throws Exception {
        var who=identity();var caller=credentials.authenticate(who.key());var event=decode(body("unknown","customer","card","1.00"));
        var uncertain=new TransactionService(store,(actor,transaction,hash,budget,attempt)->{
            writer.write(actor,transaction,hash,budget,attempt);
            throw new DataAccessResourceFailureException("Synthetic acknowledgement loss after real proxy commit");
        });
        assertThatThrownBy(()->uncertain.submit(caller,event,()->(long)limits.api().businessDeadlineMillis()))
                .isInstanceOfSatisfying(FlowFailure.class,e->{assertThat(e.unknown()).isTrue();assertThat(e.code()).isEqualTo("COMMIT_OUTCOME_UNKNOWN");});
        assertCounts(who,1,1);
        var recovered=service.submit(caller,event,()->(long)limits.api().businessDeadlineMillis());
        assertThat(recovered.created()).isFalse();assertThat(get(who,"unknown").statusCode()).isEqualTo(200);
    }
    @Test void commitTimeConstraintFailureHasNoPartialRecordsAndCanBeRetried() throws Exception {
        var who=identity();String input=body("commit-fault","customer","card","1.00");var admin=database("fraud_migrator");
        admin.execute("CREATE FUNCTION fraud.test_commit_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id='commit-fault' THEN RAISE EXCEPTION 'synthetic deferred failure' USING ERRCODE='23514'; END IF; RETURN NULL; END $$");
        admin.execute("CREATE CONSTRAINT TRIGGER test_commit_fault AFTER INSERT ON fraud.evaluations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fraud.test_commit_fault()");
        try {
            var failed=post(who,input);assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
            assertThat(json.readTree(failed.body()).get("outcome").asString()).isEqualTo("UNKNOWN");assertCounts(who,0,0);
        }finally{admin.execute("DROP TRIGGER test_commit_fault ON fraud.evaluations");admin.execute("DROP FUNCTION fraud.test_commit_fault()");}
        assertThat(post(who,input).statusCode()).isEqualTo(201);
    }
    @Test void databaseRefusesAnEventWithoutItsEvaluationAtCommit() throws Exception {
        var who=identity();var caller=credentials.authenticate(who.key());var event=decode(body("orphan","customer","card","1.00"));
        assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            store.createCustomer(who.scope(),"customer");store.lockCustomer(who.scope(),"customer");store.bindInstrument(who.scope(),"card","customer");
            store.insertEvent(caller,event,za.co.sibusiso.fraud.contracts.CanonicalTransaction.hash(who.scope(),event),Instant.now().truncatedTo(ChronoUnit.MICROS),1);
        })).isInstanceOf(RuntimeException.class).rootCause().hasMessageContaining("transaction_has_evaluation");
        assertCounts(who,0,0);
    }
    @Test void policySelectionOccursAfterCustomerLockAndUsesOneWholeNewVersion() throws Exception {
        var who=identity();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var blocked=new AtomicBoolean();
        doAnswer(invocation->{if(blocked.compareAndSet(false,true)){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test release timed out");}return invocation.callRealMethod();})
                .when(store).databaseTime();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->post(who,body("capture","customer","card","1.00")));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();String hash=activate(original.replace("25000.00","0.50"));release.countDown();
                var response=pending.get(12,TimeUnit.SECONDS);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                var evaluation=json.readTree(response.body()).get("evaluation");assertThat(evaluation.get("policyHash").asString()).isEqualTo(hash);
                assertThat(evaluation.get("decision").asString()).isEqualTo("FLAGGED");
            }finally{release.countDown();activate(original);}
        }
    }
    @Test void aPolicyChangeDuringEvaluationCannotMixThresholdAndPolicyIdentity() throws Exception {
        var who=identity();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        String oldHash=observer().queryForObject("SELECT policy_hash FROM fraud.policy_head WHERE singleton",String.class);
        doAnswer(invocation->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Test release timed out");return invocation.callRealMethod();})
                .when(high).evaluate(any(),any(),any());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->post(who,body("captured","customer","card","1.00")));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();activate(original.replace("25000.00","0.50"));release.countDown();
                var response=pending.get(12,TimeUnit.SECONDS);assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                var evaluation=json.readTree(response.body()).get("evaluation");assertThat(evaluation.get("policyHash").asString()).isEqualTo(oldHash);
                assertThat(evaluation.get("decision").asString()).isEqualTo("CLEAR");
            }finally{release.countDown();activate(original);}
        }
    }
    @Test void receiptTimeClampsSmallRegressionsAndRejectsExcessiveRegressions() throws Exception {
        var who=identity();var first=post(who,body("first-clock","customer","card","1.00"));assertThat(first.statusCode()).isEqualTo(201);
        Instant accepted=Instant.parse(json.readTree(first.body()).at("/evaluation/acceptedAt").asString());
        doReturn(accepted.minusMillis(limits.operations().maxClockRegressionMillis()/2)).when(store).databaseTime();
        var second=post(who,body("second-clock","customer","card","1.00"));assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(Instant.parse(json.readTree(second.body()).at("/evaluation/acceptedAt").asString())).isEqualTo(accepted);
        doReturn(accepted.minusMillis(limits.operations().maxClockRegressionMillis()).minusNanos(1000)).when(store).databaseTime();
        var third=post(who,body("third-clock","customer","card","1.00"));assertThat(third.statusCode()).isEqualTo(503);
        assertThat(json.readTree(third.body()).get("code").asString()).isEqualTo("CLOCK_REGRESSION");assertCounts(who,2,1);
    }
    @Test void uncertainTransactionCleanupCannotClaimConfirmedNonacceptance() throws Exception {
        var who=identity();var event=decode(body("cleanup","customer","card","1.00"));
        var uncertain=new TransactionService(store,(actor,transaction,hash,budget,attempt)->{
            throw new org.springframework.transaction.TransactionSystemException("Synthetic uncertain rollback");
        });
        assertThatThrownBy(()->uncertain.submit(credentials.authenticate(who.key()),event,()->(long)limits.api().businessDeadlineMillis()))
                .isInstanceOfSatisfying(FlowFailure.class,failure->{assertThat(failure.unknown()).isTrue();assertThat(failure.code()).isEqualTo("TRANSACTION_OUTCOME_UNKNOWN");});
    }
    @Test void deferredCommitChecksRunWithinTheConfiguredCommitReserve() throws Exception {
        var who=identity();var admin=database("fraud_migrator");
        admin.execute("CREATE FUNCTION fraud.test_commit_budget() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id='commit-budget' AND EXTRACT(EPOCH FROM current_setting('statement_timeout')::interval)*1000 <> "
                +limits.operations().commitReserveMillis()+" THEN RAISE EXCEPTION 'Commit reserve not applied'; END IF; RETURN NULL; END $$");
        admin.execute("CREATE CONSTRAINT TRIGGER test_commit_budget AFTER INSERT ON fraud.evaluations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fraud.test_commit_budget()");
        try {var result=post(who,body("commit-budget","customer","card","1.00"));assertThat(result.statusCode()).as(result.body()).isEqualTo(201);assertCounts(who,1,1);}
        finally{admin.execute("DROP TRIGGER test_commit_budget ON fraud.evaluations");admin.execute("DROP FUNCTION fraud.test_commit_budget()");}
    }
    @Test void databaseRejectsIncompleteResultsAndInvalidEvidenceIndependentlyOfTheEngine() throws Exception {
        var who=identity();var caller=credentials.authenticate(who.key());var event=decode(body("incomplete","customer","card","1.00"));
        String policyHash=observer().queryForObject("SELECT policy_hash FROM fraud.policy_head WHERE singleton",String.class);
        assertThatThrownBy(()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            store.createCustomer(who.scope(),"customer");store.lockCustomer(who.scope(),"customer");store.bindInstrument(who.scope(),"card","customer");
            store.insertEvent(caller,event,za.co.sibusiso.fraud.contracts.CanonicalTransaction.hash(who.scope(),event),Instant.now().truncatedTo(ChronoUnit.MICROS),1);
            jdbc.update("INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,decision,coverage,evaluated_at,expected_rule_count) VALUES (?,?,?::uuid,?,'synthetic-test',0,'CLEAR','COMPLETE',clock_timestamp(),5)",
                    who.scope(),event.eventId(),UUID.randomUUID().toString(),policyHash);
        })).isInstanceOf(RuntimeException.class).rootCause().hasMessageContaining("result set is incomplete");
        assertCounts(who,0,0);
        assertThat(observer().queryForObject("SELECT fraud.valid_rule_evidence(?::jsonb)",Boolean.class,"{\"evidenceSchemaVersion\":1,\"amount\":0.01}")).isTrue();
        for(String evidence:List.of("{}","{\"evidenceSchemaVersion\":0}","{\"evidenceSchemaVersion\":1,\"nested\":{}}","{\"evidenceSchemaVersion\":1,\"fact\":null}"))
            assertThat(observer().queryForObject("SELECT fraud.valid_rule_evidence(?::jsonb)",Boolean.class,evidence)).isFalse();
    }
    private String activate(String source) {
        var yaml=new StrictYaml();var snapshot=new PolicyLoader(yaml,RuleRegistry.of(BuiltInRules.definitions(),limits.api().maxRegisteredRules())).read(source);
        new RuleEngine(List.of(new HighAmountRule(), new za.co.sibusiso.fraud.evaluation.TransactionVelocityRule(), new za.co.sibusiso.fraud.evaluation.CumulativeSpendRule(), new za.co.sibusiso.fraud.evaluation.SmallPaymentBurstRule(), new ImpossibleTravelRule(limits.api().maxLocationCandidatesPerEvaluation())), limits.api().maxEvidenceFactsPerRule()).verify(snapshot);
        return database("fraud_policy_admin").queryForObject("SELECT fraud.activate_policy(?,?,false)",String.class,snapshot.policyHash(),source);
    }
    private void assertCounts(Identity who,int events,int instruments) {
        for(String table:List.of("transactions","evaluations"))assertThat(observer().queryForObject("SELECT count(*) FROM fraud."+table+" WHERE scope=?",Integer.class,who.scope())).as(table).isEqualTo(events);
        assertThat(observer().queryForObject("SELECT count(*) FROM fraud.rule_results WHERE scope=?",Integer.class,who.scope())).isEqualTo(events*5);
        assertThat(observer().queryForObject("SELECT count(*) FROM fraud.instrument_owners WHERE scope=?",Integer.class,who.scope())).isEqualTo(instruments);
    }
    private TransactionEvent decode(String input) throws Exception {
        return new TransactionDecoder(limits).decode(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),-1,"application/json",null);
    }
    private String time(){return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();}
    private String body(String id,String customer,String instrument,String amount){
        return json.writeValueAsString(Map.of("eventId",id,"customerId",customer,"instrumentId",instrument,"transactionType","CARD_PURCHASE","status","BOOKED","channel","ECOMMERCE","amount",amount,"currency","ZAR","category","GROCERIES","occurredAt",time()));
    }
    private HttpResponse<String> post(Identity who,String input) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/transactions")).timeout(Duration.ofMillis(limits.api().exampleClientDeadlineMillis()))
                .header("X-API-Key",who.key()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(Identity who,String id) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/v1/transactions/"+id)).timeout(Duration.ofSeconds(5)).header("X-API-Key",who.key()).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path){return URI.create("http://127.0.0.1:"+port+path);}
}
