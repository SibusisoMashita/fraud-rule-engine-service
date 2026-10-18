package za.co.sibusiso.fraud.evaluation.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.sibusiso.fraud.TestDatabase;
import za.co.sibusiso.fraud.TestImages;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.evaluation.application.EvaluationQuery;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class EvaluationQueryMigrationTest {
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer(DockerImageName.parse(
            TestImages.POSTGRES).asCompatibleSubstituteFor("postgres"))
            .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);
    @Test void migrationBackfillsNavigationFieldsWithoutChangingCommittedResourcesOrWritePrivileges() throws Exception {
        TestDatabase.initialize(DATABASE,7);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"fraud_migrator",TestDatabase.PASSWORD));
        var runtime=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"fraud_runtime",TestDatabase.PASSWORD));
        var store=new JdbcTransactionStore(runtime);Instant accepted=Instant.parse("2026-10-16T00:00:00Z");
        jdbc.update("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES ('producer','migration',true,ARRAY['INGEST'],ARRAY[]::text[])");
        var yaml=new StrictYaml();var snapshot=new PolicyLoader(yaml,RuleRegistry.of(BuiltInRules.definitions(),64))
                .read(yaml.readText(new org.springframework.core.io.ClassPathResource("config/policy.example.yaml")));
        var input=new TransactionEvent("event","customer","card",TransactionEvent.Type.CARD_PURCHASE,TransactionEvent.Status.BOOKED,
                TransactionEvent.Channel.ECOMMERCE,Money.parse("ZAR","1"),"SYNTHETIC",accepted,null);
        var results=snapshot.rules().keySet().stream().map(id->new RuleResult(id,"1",RuleResult.Status.NO_MATCH,"SYNTHETIC_FIXTURE",
                Map.of("evidenceSchemaVersion",new RuleResult.Number(BigDecimal.ONE)))).toList();
        new TransactionTemplate(new JdbcTransactionManager(runtime.getDataSource())).executeWithoutResult(status->{
            store.createCustomer("migration","customer");store.lockCustomer("migration","customer");store.bindInstrument("migration","card","customer");
            store.insertEvent(new Caller("producer","migration",Set.of(Caller.Role.INGEST),Set.of(),1),input,"0".repeat(64),accepted,1);
            store.insertEvaluation("migration","event",UUID.randomUUID(),snapshot.policyHash(),"synthetic-migration-fixture",0,accepted,
                    new EvaluationOutcome(EvaluationOutcome.Decision.CLEAR,EvaluationOutcome.Coverage.COMPLETE,results));
            store.advance("migration","customer",accepted,1);
        });
        String before=store.find("migration","event").orElseThrow().json();
        var flyway=Flyway.configure().dataSource(DATABASE.getJdbcUrl(),"fraud_migrator",TestDatabase.PASSWORD).defaultSchema("fraud").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(store.find("migration","event").orElseThrow().json()).isEqualTo(before);
        assertThat(runtime.queryForObject("SELECT customer_id FROM fraud.evaluations WHERE scope='migration'",String.class)).isEqualTo("customer");
        assertThat(new JdbcEvaluationQueryStore(runtime).page("migration",new EvaluationQuery.Window("customer",null,
                accepted,accepted.plusSeconds(1),1,null))).hasSize(1);
        assertThatThrownBy(()->runtime.update("UPDATE fraud.evaluations SET accepted_at=clock_timestamp()"))
                .rootCause().hasMessageContaining("permission denied");
        var second=new TransactionEvent("second","second-customer","second-card",TransactionEvent.Type.CARD_PURCHASE,
                TransactionEvent.Status.BOOKED,TransactionEvent.Channel.ECOMMERCE,Money.parse("ZAR","1"),"SYNTHETIC",accepted,null);
        new TransactionTemplate(new JdbcTransactionManager(runtime.getDataSource())).executeWithoutResult(status->{
            store.createCustomer("migration","second-customer");store.lockCustomer("migration","second-customer");
            store.bindInstrument("migration","second-card","second-customer");
            store.insertEvent(new Caller("producer","migration",Set.of(Caller.Role.INGEST),Set.of(),1),second,"1".repeat(64),accepted,1);
            runtime.update("INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,decision,coverage,evaluated_at,expected_rule_count,customer_id,accepted_at) VALUES ('migration','second',?,?, 'synthetic-trigger-fixture',0,'CLEAR','COMPLETE',?,5,'forged','2000-01-01T00:00:00Z')",
                    UUID.randomUUID(),snapshot.policyHash(),accepted.atOffset(java.time.ZoneOffset.UTC));
            for(var result:results)runtime.update("INSERT INTO fraud.rule_results(scope,event_id,rule_id,algorithm_version,status,reason_code,evidence) VALUES ('migration','second',?,'1','NO_MATCH','SYNTHETIC_FIXTURE','{\"evidenceSchemaVersion\":1}')",result.ruleId().value());
            store.advance("migration","second-customer",accepted,1);
        });
        assertThat(new JdbcEvaluationQueryStore(runtime).page("migration",new EvaluationQuery.Window("forged",null,
                accepted,accepted.plusSeconds(1),1,null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fraud.evaluations e JOIN fraud.transactions t USING(scope,event_id) WHERE e.customer_id=t.customer_id AND e.accepted_at=t.accepted_at",Integer.class)).isEqualTo(2);
    }
}
