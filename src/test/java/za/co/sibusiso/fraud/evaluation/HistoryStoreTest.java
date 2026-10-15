package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.sibusiso.fraud.TestDatabase;
import static org.assertj.core.api.Assertions.*;

/** Real database fixtures isolate receipt-window, watermark and index behaviour from HTTP. */
@Testcontainers
class HistoryStoreTest {
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static JdbcTransactionStore store;
    static String policyHash;
    static BigDecimal highThreshold;
    final Instant accepted=Instant.parse("2026-10-15T12:00:00Z");
    @BeforeAll static void initialize() throws Exception {
        TestDatabase.initialize(DATABASE);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"fraud_migrator",TestDatabase.PASSWORD));
        store=new JdbcTransactionStore(new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"fraud_runtime",TestDatabase.PASSWORD)));
        var yaml=new za.co.sibusiso.fraud.configuration.StrictYaml();
        String source=yaml.readText(new org.springframework.core.io.ClassPathResource("config/policy.example.yaml"))
                .replace("transactionVelocity:\n    enabled: true","transactionVelocity:\n    enabled: false")
                .replace("cumulativeSpend:\n    enabled: true","cumulativeSpend:\n    enabled: false");
        var policy=new za.co.sibusiso.fraud.configuration.PolicyLoader(yaml,
                za.co.sibusiso.fraud.configuration.RuleRegistry.of(za.co.sibusiso.fraud.configuration.BuiltInRules.definitions(),64)).read(source);
        highThreshold=((za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.HighAmount)policy.rules().get(new za.co.sibusiso.fraud.domain.RuleId("HIGH_AMOUNT")).settings()).thresholds().get("ZAR").value();
        var admin=new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),"fraud_policy_admin",TestDatabase.PASSWORD));
        policyHash=admin.queryForObject("SELECT fraud.activate_policy(?,?,false)",String.class,policy.policyHash(),source);
    }
    @Test void receiptWindowIsOpenAtLowerEndClosedAtUpperEndAndExcludesBeyondWatermark() {
        String scope=fixtureScope();
        append(scope,"customer","lower","ZAR","1",accepted.minusSeconds(60),1);
        append(scope,"customer","inside","ZAR","2",accepted.minusSeconds(60).plusNanos(1000),2);
        append(scope,"customer","upper","ZAR","3",accepted,3);
        append(scope,"customer","next-sequence","ZAR","100",accepted,4);
        append(scope,"customer","future-receipt","ZAR","1000",accepted.plusNanos(1000),5);
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Count(60),accepted,3)).isEqualByComparingTo("2");
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Spend(60,"ZAR"),accepted,3)).isEqualByComparingTo("5");
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Count(60),accepted,4)).isEqualByComparingTo("3");
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Count(61),accepted,3)).isEqualByComparingTo("3");
    }
    @Test void scopeCustomerCurrencyAndReceiptTimeDetermineHistoryNotEventTimeOrDecision() {
        String scope=fixtureScope(),other=fixtureScope();
        append(scope,"customer","zar","ZAR","70000.01",accepted,1);
        append(scope,"customer","usd","USD","0.02",accepted,2);
        append(scope,"other-customer","other","ZAR","100",accepted,1);
        append(other,"customer","other-scope","ZAR","100",accepted,1);
        // Fixtures have deliberately old occurredAt; receipt-time rules must still include them.
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Count(60),accepted,2)).isEqualByComparingTo("2");
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Spend(60,"ZAR"),accepted,2)).isEqualByComparingTo("70000.01");
        assertThat(store.aggregate(scope,"customer",new HistoryQuery.Spend(60,"USD"),accepted,2)).isEqualByComparingTo("0.02");
        assertThat(store.aggregate(scope,"missing",new HistoryQuery.Count(60),accepted,9)).isZero();
        assertThat(store.aggregate(scope,"missing",new HistoryQuery.Spend(60,"ZAR"),accepted,9)).isZero();
    }
    @Test void aggregateIncludesAllHistoryBeyondPublicPageLimitsAndUsesCoveringReceiptIndexes() {
        String scope=fixtureScope();
        // 6,000 valid relational fixture rows: 20 customers with 300 receipts each.
        bulk(scope,6000,20);
        var count=new HistoryQuery.Count(600);var spend=new HistoryQuery.Spend(600,"ZAR");
        assertThat(store.aggregate(scope,"customer-1",count,accepted,300)).isEqualByComparingTo("300");
        assertThat(store.aggregate(scope,"customer-1",spend,accepted,300)).isEqualByComparingTo("3.00");
        jdbc.execute("VACUUM ANALYZE fraud.transactions");
        String countPlan=plan(scope,"customer-1",count,300);
        String spendPlan=plan(scope,"customer-1",spend,300);
        assertThat(countPlan).contains("Index Only Scan").containsAnyOf("transactions_customer_receipt_count","transactions_customer_currency_receipt_spend");
        assertThat(spendPlan).contains("Index Only Scan").contains("transactions_customer_currency_receipt_spend");
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        System.out.println("History query fixture: 6000 receipts; 300 target rows; count="
                +json.readTree(countPlan).findValues("Index Name")+"; spend="+json.readTree(spendPlan).findValues("Index Name"));
    }
    private String plan(String scope,String customer,HistoryQuery query,long watermark) {
        return jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+JdbcTransactionStore.historySql(query),String.class,
                JdbcTransactionStore.historyArguments(scope,customer,query,accepted,watermark));
    }
    private String fixtureScope() {
        String scope="history-"+UUID.randomUUID();
        jdbc.update("INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['INGEST'],ARRAY[]::text[])",scope,scope);
        return scope;
    }
    private void append(String scope,String customer,String event,String currency,String amount,Instant receipt,long sequence) {
        bulkRow(scope,customer,event,currency,amount,receipt,sequence);
    }
    private void bulkRow(String scope,String customer,String event,String currency,String amount,Instant receipt,long sequence) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO fraud.customer_processing_state(scope,customer_id,last_accepted_at,customer_sequence) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",scope,customer,utc(receipt),sequence);
            jdbc.update("INSERT INTO fraud.instrument_owners(scope,instrument_id,customer_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",scope,customer,customer);
            jdbc.update("""
                    INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,transaction_type,status,channel,
                        amount,currency,category,occurred_at,accepted_at,customer_sequence,producer_id,source_permission_version,source_permissions,input_json)
                    VALUES (?,?,?,?,repeat('0',64),1,'CARD_PURCHASE','BOOKED','ECOMMERCE',?::numeric,?,'SYNTHETIC',?::timestamptz,?,?,?,1,'[]','{}')
                    """,scope,event,customer,customer,amount,currency,utc(accepted.minusSeconds(86400)),utc(receipt),sequence,scope);
            evaluationRows(scope);
        });
    }
    private void bulk(String scope,int size,int customers) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO fraud.customer_processing_state(scope,customer_id,last_accepted_at,customer_sequence) SELECT ?, 'customer-'||n, ?::timestamptz, ? FROM generate_series(0,?-1) n",scope,utc(accepted),size/customers,customers);
            jdbc.update("INSERT INTO fraud.instrument_owners(scope,instrument_id,customer_id) SELECT scope,customer_id,customer_id FROM fraud.customer_processing_state WHERE scope=?",scope);
            jdbc.update("""
                    INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,transaction_type,status,channel,
                        amount,currency,category,occurred_at,accepted_at,customer_sequence,producer_id,source_permission_version,source_permissions,input_json)
                    SELECT ?, 'fixture-'||n, 'customer-'||(n % ?), 'customer-'||(n % ?), repeat('0',64),1,'CARD_PURCHASE','BOOKED','ECOMMERCE',
                        0.01,'ZAR','SYNTHETIC',?::timestamptz,?::timestamptz,n / ? + 1,?,1,'[]','{}' FROM generate_series(0,?-1) n
                    """,scope,customers,customers,utc(accepted.minusSeconds(86400)),utc(accepted),customers,scope,size);
            evaluationRows(scope);
        });
    }
    private void evaluationRows(String scope) {
        jdbc.update("""
                INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,decision,coverage,evaluated_at,expected_rule_count)
                SELECT t.scope,t.event_id,gen_random_uuid(),?,'synthetic-history-fixture',t.customer_sequence-1,CASE WHEN t.amount>? THEN 'FLAGGED' ELSE 'CLEAR' END,'COMPLETE',t.accepted_at,5
                FROM fraud.transactions t WHERE t.scope=? AND NOT EXISTS (SELECT 1 FROM fraud.evaluations e WHERE e.scope=t.scope AND e.event_id=t.event_id)
                """,policyHash,highThreshold,scope);
        jdbc.update("""
                INSERT INTO fraud.rule_results(scope,event_id,rule_id,algorithm_version,status,reason_code,evidence)
                SELECT e.scope,e.event_id,r,'1',CASE WHEN r='HIGH_AMOUNT' THEN CASE WHEN e.decision='FLAGGED' THEN 'MATCH' ELSE 'NO_MATCH' END ELSE 'DISABLED' END,'SYNTHETIC_FIXTURE','{"evidenceSchemaVersion":1}'
                FROM fraud.evaluations e CROSS JOIN unnest(ARRAY['HIGH_AMOUNT','TRANSACTION_VELOCITY','CUMULATIVE_SPEND','IMPOSSIBLE_TRAVEL','SMALL_PAYMENT_BURST_THEN_LARGE']) r
                WHERE e.scope=? ON CONFLICT DO NOTHING
                """,scope);
    }
    private OffsetDateTime utc(Instant time) {return time.atOffset(ZoneOffset.UTC);}
}
