package za.co.sibusiso.fraud.evaluation.persistence;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import za.co.sibusiso.fraud.TestDatabase;
import za.co.sibusiso.fraud.TestImages;
import za.co.sibusiso.fraud.evaluation.application.EvaluationQuery;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.history.LocationCandidates;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsHistory;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Real database fixtures isolate receipt-window, watermark and index behaviour from HTTP. */
@Testcontainers
class HistoryStoreTest {
    @Container
    static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(
                            DockerImageName.parse(TestImages.POSTGRES)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);

    static JdbcTemplate jdbc;
    static JdbcTransactionStore store;
    static String policyHash;
    static BigDecimal highThreshold;
    final Instant accepted = Instant.parse("2026-10-15T12:00:00Z");

    @BeforeAll
    static void initialize() throws Exception {
        TestDatabase.initialize(DATABASE);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                DATABASE.getJdbcUrl(), "fraud_migrator", TestDatabase.PASSWORD));
        store =
                new JdbcTransactionStore(
                        new JdbcTemplate(
                                new DriverManagerDataSource(
                                        DATABASE.getJdbcUrl(),
                                        "fraud_runtime",
                                        TestDatabase.PASSWORD)));
        var yaml = new za.co.sibusiso.fraud.configuration.StrictYaml();
        String source =
                yaml.readText(
                                new org.springframework.core.io.ClassPathResource(
                                        "config/policy.example.yaml"))
                        .replace(
                                "transactionVelocity:\n    enabled: true",
                                "transactionVelocity:\n    enabled: false")
                        .replace(
                                "cumulativeSpend:\n    enabled: true",
                                "cumulativeSpend:\n    enabled: false")
                        .replace(
                                "impossibleTravel:\n    enabled: true",
                                "impossibleTravel:\n    enabled: false")
                        .replace(
                                "smallPaymentBurstThenLarge:\n    enabled: true",
                                "smallPaymentBurstThenLarge:\n    enabled: false");
        var policy =
                new za.co.sibusiso.fraud.policy.definition.PolicyLoader(
                                yaml,
                                za.co.sibusiso.fraud.policy.definition.RuleRegistry.of(
                                        za.co.sibusiso.fraud.policy.definition.BuiltInRules
                                                .definitions(),
                                        64))
                        .read(source);
        highThreshold =
                ((za.co.sibusiso.fraud.policy.definition.BuiltInRuleSettings.HighAmount)
                                policy.rules()
                                        .get(new za.co.sibusiso.fraud.domain.RuleId("HIGH_AMOUNT"))
                                        .settings())
                        .thresholds()
                        .get("ZAR")
                        .value();
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                DATABASE.getJdbcUrl(),
                                "fraud_policy_admin",
                                TestDatabase.PASSWORD));
        policyHash =
                admin.queryForObject(
                        "SELECT fraud.activate_policy(?,?,false)",
                        String.class,
                        policy.policyHash(),
                        source);
    }

    @Test
    void receiptWindowIsOpenAtLowerEndClosedAtUpperEndAndExcludesBeyondWatermark() {
        String scope = fixtureScope();
        append(scope, "customer", "lower", "ZAR", "1", accepted.minusSeconds(60), 1);
        append(
                scope,
                "customer",
                "inside",
                "ZAR",
                "2",
                accepted.minusSeconds(60).plusNanos(1000),
                2);
        append(scope, "customer", "upper", "ZAR", "3", accepted, 3);
        append(scope, "customer", "next-sequence", "ZAR", "100", accepted, 4);
        append(scope, "customer", "future-receipt", "ZAR", "1000", accepted.plusNanos(1000), 5);
        assertThat(store.aggregate(scope, "customer", new HistoryQuery.Count(60), accepted, 3))
                .isEqualByComparingTo("2");
        assertThat(
                        store.aggregate(
                                scope, "customer", new HistoryQuery.Spend(60, "ZAR"), accepted, 3))
                .isEqualByComparingTo("5");
        assertThat(store.aggregate(scope, "customer", new HistoryQuery.Count(60), accepted, 4))
                .isEqualByComparingTo("3");
        assertThat(store.aggregate(scope, "customer", new HistoryQuery.Count(61), accepted, 3))
                .isEqualByComparingTo("3");
    }

    @Test
    void scopeCustomerCurrencyAndReceiptTimeDetermineHistoryNotEventTimeOrDecision() {
        String scope = fixtureScope(), other = fixtureScope();
        append(scope, "customer", "zar", "ZAR", "70000.01", accepted, 1);
        append(scope, "customer", "usd", "USD", "0.02", accepted, 2);
        append(scope, "other-customer", "other", "ZAR", "100", accepted, 1);
        append(other, "customer", "other-scope", "ZAR", "100", accepted, 1);
        // Fixtures have deliberately old occurredAt; receipt-time rules must still include them.
        assertThat(store.aggregate(scope, "customer", new HistoryQuery.Count(60), accepted, 2))
                .isEqualByComparingTo("2");
        assertThat(
                        store.aggregate(
                                scope, "customer", new HistoryQuery.Spend(60, "ZAR"), accepted, 2))
                .isEqualByComparingTo("70000.01");
        assertThat(
                        store.aggregate(
                                scope, "customer", new HistoryQuery.Spend(60, "USD"), accepted, 2))
                .isEqualByComparingTo("0.02");
        assertThat(store.aggregate(scope, "missing", new HistoryQuery.Count(60), accepted, 9))
                .isZero();
        assertThat(
                        store.aggregate(
                                scope, "missing", new HistoryQuery.Spend(60, "ZAR"), accepted, 9))
                .isZero();
    }

    @Test
    void aggregateIncludesAllHistoryBeyondPublicPageLimitsAndUsesCoveringReceiptIndexes() {
        String scope = fixtureScope();
        // 6,000 valid relational fixture rows: 20 customers with 300 receipts each.
        bulk(scope, 6000, 20);
        var count = new HistoryQuery.Count(600);
        var spend = new HistoryQuery.Spend(600, "ZAR");
        assertThat(store.aggregate(scope, "customer-1", count, accepted, 300))
                .isEqualByComparingTo("300");
        assertThat(store.aggregate(scope, "customer-1", spend, accepted, 300))
                .isEqualByComparingTo("3.00");
        jdbc.execute("VACUUM ANALYZE fraud.transactions");
        String countPlan = plan(scope, "customer-1", count, 300);
        String spendPlan = plan(scope, "customer-1", spend, 300);
        assertThat(countPlan)
                .contains("Index Only Scan")
                .containsAnyOf(
                        "transactions_customer_receipt_count",
                        "transactions_customer_currency_receipt_spend");
        assertThat(spendPlan)
                .contains("Index Only Scan")
                .contains("transactions_customer_currency_receipt_spend");
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        System.out.println(
                "History query fixture: 6000 receipts; 300 target rows; count="
                        + json.readTree(countPlan).findValues("Index Name")
                        + "; spend="
                        + json.readTree(spendPlan).findValues("Index Name"));
    }

    @Test
    void queryKeysUseOrderedIndexesAndHydrateOnlyTheBoundedPage() {
        String scope = fixtureScope();
        bulk(scope, 6000, 20);
        jdbc.execute("VACUUM ANALYZE fraud.evaluations");
        var queries = new JdbcEvaluationQueryStore(jdbc);
        for (String customer : new String[] {null, "customer-1"}) {
            var window =
                    new EvaluationQuery.Window(
                            customer,
                            null,
                            accepted.minusSeconds(1),
                            accepted.plusSeconds(1),
                            20,
                            null);
            var page = queries.page(scope, window);
            assertThat(page).hasSize(21);
            assertThat(page)
                    .allSatisfy(row -> assertThat(row.position().acceptedAt()).isEqualTo(accepted));
            String plan =
                    jdbc.queryForObject(
                            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                                    + JdbcEvaluationQueryStore.keysSql(window),
                            String.class,
                            JdbcEvaluationQueryStore.arguments(scope, window));
            assertThat(plan)
                    .contains("Index Scan")
                    .contains(
                            customer == null
                                    ? "evaluations_scope_accepted_id"
                                    : "evaluations_customer_accepted_id");
        }
        var flagged =
                new EvaluationQuery.Window(
                        null,
                        za.co.sibusiso.fraud.domain.EvaluationOutcome.Decision.FLAGGED,
                        accepted.minusSeconds(1),
                        accepted.plusSeconds(1),
                        20,
                        null);
        String plan =
                jdbc.queryForObject(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                                + JdbcEvaluationQueryStore.keysSql(flagged),
                        String.class,
                        JdbcEvaluationQueryStore.arguments(scope, flagged));
        assertThat(plan).contains("evaluations_decision_accepted_id");
        assertThat(queries.page(scope, flagged)).isEmpty();
    }

    @Test
    void smallPaymentsUseAnOpenEventTimeWindowInclusiveAmountAndCapturedWatermark() {
        String scope = fixtureScope(), other = fixtureScope();
        sequenceRow(scope, "customer", "lower", "ZAR", "1", accepted.minusSeconds(60), 1);
        sequenceRow(
                scope,
                "customer",
                "inside-lower",
                "ZAR",
                "0.10",
                accepted.minusSeconds(60).plusNanos(1000),
                2);
        sequenceRow(
                scope, "customer", "inside-upper", "ZAR", "50.00", accepted.minusNanos(1000), 3);
        sequenceRow(scope, "customer", "equal-time", "ZAR", "1", accepted, 4);
        sequenceRow(scope, "customer", "later-time", "ZAR", "1", accepted.plusNanos(1000), 5);
        sequenceRow(
                scope, "customer", "above-small", "ZAR", "50.0001", accepted.minusSeconds(1), 6);
        sequenceRow(scope, "customer", "foreign-currency", "USD", "1", accepted.minusSeconds(1), 7);
        sequenceRow(
                scope, "customer", "beyond-watermark", "ZAR", "0.20", accepted.minusSeconds(1), 8);
        sequenceRow(
                scope,
                "another-customer",
                "other-customer",
                "ZAR",
                "1",
                accepted.minusSeconds(1),
                1);
        sequenceRow(other, "customer", "other-scope", "ZAR", "1", accepted.minusSeconds(1), 1);
        var query = new SmallPaymentsQuery(accepted, 60, "ZAR", new BigDecimal("50"));
        var first = store.smallPayments(scope, "customer", query, 7);
        assertThat(first.count()).isEqualTo(2);
        assertThat(first.total()).isEqualByComparingTo("50.10");
        var next = store.smallPayments(scope, "customer", query, 8);
        assertThat(next.count()).isEqualTo(3);
        assertThat(next.total()).isEqualByComparingTo("50.30");
        physical(
                scope,
                "another-card",
                "another-instrument",
                accepted.minusSeconds(1),
                9,
                "0.1",
                false);
        assertThat(store.smallPayments(scope, "customer", query, 9).count()).isEqualTo(4);
        assertThat(store.smallPayments(scope, "missing", query, 9))
                .isEqualTo(new SmallPaymentsHistory(0, BigDecimal.ZERO));
        assertThat(
                        store.smallPayments(
                                        scope,
                                        "customer",
                                        new SmallPaymentsQuery(
                                                accepted, 61, "ZAR", new BigDecimal("50")),
                                        7)
                                .count())
                .isEqualTo(3);
        assertThat(
                        store.smallPayments(
                                        scope,
                                        "customer",
                                        new SmallPaymentsQuery(
                                                accepted, 60, "ZAR", new BigDecimal("0.10")),
                                        7)
                                .count())
                .isEqualTo(1);
    }

    @Test
    void smallPaymentAggregateIncludesHistoryBeyondPageLimitsUsingItsCoveringEventIndex() {
        String scope = fixtureScope();
        bulk(scope, 6000, 20);
        jdbc.execute("VACUUM ANALYZE fraud.transactions");
        var query =
                new SmallPaymentsQuery(
                        accepted.minusSeconds(86400).plusSeconds(6000),
                        6001,
                        "ZAR",
                        new BigDecimal("0.01"));
        var history = store.smallPayments(scope, "customer-1", query, 300);
        assertThat(history.count()).isEqualTo(300);
        assertThat(history.total()).isEqualByComparingTo("3.00");
        String plan =
                jdbc.queryForObject(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                                + JdbcTransactionStore.smallPaymentsSql(),
                        String.class,
                        JdbcTransactionStore.smallPaymentsArguments(
                                scope, "customer-1", query, 300));
        assertThat(plan)
                .contains("Index Only Scan")
                .contains("transactions_customer_currency_event_small_payments");
    }

    private void sequenceRow(
            String scope,
            String customer,
            String event,
            String currency,
            String amount,
            Instant occurred,
            long sequence) {
        bulkRow(scope, customer, event, currency, amount, accepted, sequence, occurred);
    }

    @Test
    void locationLookupUsesInstrumentEventTimeIndexWithoutForcedPlannerSettings() {
        String scope = fixtureScope();
        bulk(scope, 6000, 20);
        jdbc.execute("VACUUM ANALYZE fraud.transactions");
        var query =
                new LocationQuery(
                        "customer-1",
                        accepted.minusSeconds(86400).plusSeconds(3000),
                        60,
                        300,
                        new BigDecimal("5"),
                        java.util.List.of(
                                za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource
                                        .VERIFIED_CARD_PRESENT_TERMINAL),
                        java.util.List.of(
                                za.co.sibusiso.fraud.domain.TransactionEvent.Channel.CARD_PRESENT));
        assertThat(store.locations(scope, "customer-1", query, 300).observations()).hasSize(6);
        String plan =
                jdbc.queryForObject(
                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                                + JdbcTransactionStore.locationSql(),
                        String.class,
                        JdbcTransactionStore.locationArguments(scope, "customer-1", query, 300));
        assertThat(plan).contains("transactions_instrument_location_time");
    }

    @Test
    void locationHistoryUsesEventTimeBothDirectionsAndAdmissionWatermark() {
        String scope = fixtureScope(), other = fixtureScope();
        physical(scope, "card", "lower", accepted.minusSeconds(60), 1, "0.1", false);
        physical(scope, "card", "upper", accepted.plusSeconds(60), 2, "0.1", false);
        physical(scope, "card", "same", accepted, 3, "5", false);
        physical(scope, "card", "later-sequence", accepted, 4, "0.1", false);
        physical(
                scope,
                "card",
                "too-old",
                accepted.minusSeconds(60).minusNanos(1000),
                5,
                "0.1",
                false);
        physical(
                scope,
                "card",
                "too-new",
                accepted.plusSeconds(60).plusNanos(1000),
                6,
                "0.1",
                false);
        physical(scope, "card", "uncertain", accepted, 7, "5.000001", false);
        physical(scope, "card", "atm-source", accepted, 8, "0.1", true);
        physical(scope, "other-card", "other-instrument", accepted, 9, "0.1", false);
        physical(other, "card", "other-scope", accepted, 1, "0.1", false);
        var query = locationQuery(3);
        var first = store.locations(scope, "customer", query, 3);
        assertThat(first.overflow()).isFalse();
        assertThat(first.observations())
                .extracting(LocationCandidates.Observation::eventId)
                .containsExactly("lower", "same", "upper");
        var all = store.locations(scope, "customer", locationQuery(10), 9);
        assertThat(all.observations())
                .extracting(LocationCandidates.Observation::eventId)
                .containsExactly("lower", "later-sequence", "same", "upper");
        assertThat(store.locations(scope, "other-customer", locationQuery(10), 9).observations())
                .isEmpty();
        var overflow = store.locations(scope, "customer", locationQuery(2), 3);
        assertThat(overflow.overflow()).isTrue();
        assertThat(overflow.observations()).hasSize(3);
    }

    private LocationQuery locationQuery(int cap) {
        return new LocationQuery(
                "card",
                accepted,
                60,
                cap,
                new BigDecimal("5"),
                java.util.List.of(
                        za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource
                                .VERIFIED_CARD_PRESENT_TERMINAL),
                java.util.List.of(
                        za.co.sibusiso.fraud.domain.TransactionEvent.Channel.CARD_PRESENT));
    }

    private void physical(
            String scope,
            String instrument,
            String id,
            Instant occurred,
            long sequence,
            String accuracy,
            boolean atm) {
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(
                                jdbc.getDataSource()));
        tx.executeWithoutResult(
                status -> {
                    var writer = new JdbcTransactionStore(jdbc);
                    writer.createCustomer(scope, "customer");
                    writer.lockCustomer(scope, "customer");
                    writer.bindInstrument(scope, instrument, "customer");
                    var source =
                            atm
                                    ? za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource
                                            .VERIFIED_ATM_TERMINAL
                                    : za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource
                                            .VERIFIED_CARD_PRESENT_TERMINAL;
                    var caller =
                            new za.co.sibusiso.fraud.ingress.security.Caller(
                                    scope,
                                    scope,
                                    java.util.Set.of(
                                            za.co.sibusiso.fraud.ingress.security.Caller.Role
                                                    .INGEST),
                                    java.util.Set.of(source),
                                    1);
                    var input =
                            new za.co.sibusiso.fraud.domain.TransactionEvent(
                                    id,
                                    "customer",
                                    instrument,
                                    atm
                                            ? za.co.sibusiso.fraud.domain.TransactionEvent.Type
                                                    .ATM_WITHDRAWAL
                                            : za.co.sibusiso.fraud.domain.TransactionEvent.Type
                                                    .CARD_PURCHASE,
                                    za.co.sibusiso.fraud.domain.TransactionEvent.Status.BOOKED,
                                    atm
                                            ? za.co.sibusiso.fraud.domain.TransactionEvent.Channel
                                                    .ATM
                                            : za.co.sibusiso.fraud.domain.TransactionEvent.Channel
                                                    .CARD_PRESENT,
                                    za.co.sibusiso.fraud.domain.Money.parse("ZAR", "1"),
                                    "SYNTHETIC",
                                    occurred,
                                    new za.co.sibusiso.fraud.domain.TransactionEvent
                                            .PhysicalLocation(
                                            new BigDecimal("-33.9249"),
                                            new BigDecimal("18.4241"),
                                            new BigDecimal(accuracy),
                                            source,
                                            "Cape Town"));
                    writer.insertEvent(
                            caller,
                            input,
                            za.co.sibusiso.fraud.contracts.CanonicalTransaction.hash(scope, input),
                            accepted,
                            sequence);
                    evaluationRows(scope);
                    writer.advance(scope, "customer", accepted, sequence);
                });
    }

    private String plan(String scope, String customer, HistoryQuery query, long watermark) {
        return jdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + JdbcTransactionStore.historySql(query),
                String.class,
                JdbcTransactionStore.historyArguments(scope, customer, query, accepted, watermark));
    }

    private String fixtureScope() {
        String scope = "history-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['INGEST'],ARRAY['VERIFIED_ATM_TERMINAL','VERIFIED_CARD_PRESENT_TERMINAL'])",
                scope,
                scope);
        return scope;
    }

    private void append(
            String scope,
            String customer,
            String event,
            String currency,
            String amount,
            Instant receipt,
            long sequence) {
        bulkRow(scope, customer, event, currency, amount, receipt, sequence);
    }

    private void bulkRow(
            String scope,
            String customer,
            String event,
            String currency,
            String amount,
            Instant receipt,
            long sequence) {
        bulkRow(
                scope,
                customer,
                event,
                currency,
                amount,
                receipt,
                sequence,
                accepted.minusSeconds(86400));
    }

    private void bulkRow(
            String scope,
            String customer,
            String event,
            String currency,
            String amount,
            Instant receipt,
            long sequence,
            Instant occurred) {
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(
                                jdbc.getDataSource()));
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "INSERT INTO fraud.customer_processing_state(scope,customer_id,last_accepted_at,customer_sequence) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                            scope,
                            customer,
                            utc(receipt),
                            sequence);
                    jdbc.update(
                            "INSERT INTO fraud.instrument_owners(scope,instrument_id,customer_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                            scope,
                            customer,
                            customer);
                    jdbc.update(
                            """
                    INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,transaction_type,status,channel,
                        amount,currency,category,occurred_at,accepted_at,customer_sequence,producer_id,source_permission_version,source_permissions,input_json)
                    VALUES (?,?,?,?,repeat('0',64),1,'CARD_PURCHASE','BOOKED','ECOMMERCE',?::numeric,?,'SYNTHETIC',?::timestamptz,?,?,?,1,'[]','{}')
                    """,
                            scope,
                            event,
                            customer,
                            customer,
                            amount,
                            currency,
                            utc(occurred),
                            utc(receipt),
                            sequence,
                            scope);
                    evaluationRows(scope);
                });
    }

    private void bulk(String scope, int size, int customers) {
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.support.JdbcTransactionManager(
                                jdbc.getDataSource()));
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "INSERT INTO fraud.customer_processing_state(scope,customer_id,last_accepted_at,customer_sequence) SELECT ?, 'customer-'||n, ?::timestamptz, ? FROM generate_series(0,?-1) n",
                            scope,
                            utc(accepted),
                            size / customers,
                            customers);
                    jdbc.update(
                            "INSERT INTO fraud.instrument_owners(scope,instrument_id,customer_id) SELECT scope,customer_id,customer_id FROM fraud.customer_processing_state WHERE scope=?",
                            scope);
                    jdbc.update(
                            """
                    INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,transaction_type,status,channel,
                        amount,currency,category,occurred_at,accepted_at,customer_sequence,producer_id,source_permission_version,source_permissions,latitude,longitude,accuracy_km,location_source,input_json)
                    SELECT ?, 'fixture-'||n, 'customer-'||(n % ?), 'customer-'||(n % ?), repeat('0',64),1,'CARD_PURCHASE','BOOKED','CARD_PRESENT',
                        0.01,'ZAR','SYNTHETIC',?::timestamptz + n * interval '1 second',?::timestamptz,n / ? + 1,?,1,'["VERIFIED_CARD_PRESENT_TERMINAL"]',-33.9249,18.4241,0.1,'VERIFIED_CARD_PRESENT_TERMINAL','{}' FROM generate_series(0,?-1) n
                    """,
                            scope,
                            customers,
                            customers,
                            utc(accepted.minusSeconds(86400)),
                            utc(accepted),
                            customers,
                            scope,
                            size);
                    evaluationRows(scope);
                });
    }

    private void evaluationRows(String scope) {
        jdbc.update(
                """
                INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,decision,coverage,evaluated_at,expected_rule_count)
                SELECT t.scope,t.event_id,gen_random_uuid(),?,'synthetic-history-fixture',t.customer_sequence-1,CASE WHEN t.amount>? THEN 'FLAGGED' ELSE 'CLEAR' END,'COMPLETE',t.accepted_at,5
                FROM fraud.transactions t WHERE t.scope=? AND NOT EXISTS (SELECT 1 FROM fraud.evaluations e WHERE e.scope=t.scope AND e.event_id=t.event_id)
                """,
                policyHash,
                highThreshold,
                scope);
        jdbc.update(
                """
                INSERT INTO fraud.rule_results(scope,event_id,rule_id,algorithm_version,status,reason_code,evidence)
                SELECT e.scope,e.event_id,r,'1',CASE WHEN r='HIGH_AMOUNT' THEN CASE WHEN e.decision='FLAGGED' THEN 'MATCH' ELSE 'NO_MATCH' END ELSE 'DISABLED' END,'SYNTHETIC_FIXTURE','{"evidenceSchemaVersion":1}'
                FROM fraud.evaluations e CROSS JOIN unnest(ARRAY['HIGH_AMOUNT','TRANSACTION_VELOCITY','CUMULATIVE_SPEND','IMPOSSIBLE_TRAVEL','SMALL_PAYMENT_BURST_THEN_LARGE']) r
                WHERE e.scope=? ON CONFLICT DO NOTHING
                """,
                scope);
    }

    private OffsetDateTime utc(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }
}
