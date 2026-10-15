package za.co.sibusiso.fraud.evaluation;

import java.time.Instant;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import za.co.sibusiso.fraud.contracts.CanonicalJson;
import za.co.sibusiso.fraud.contracts.CanonicalTransaction;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.ingress.Caller;

@Repository
@Profile("!migrate & !policy-admin")
public class JdbcTransactionStore implements TransactionStore {
    private final JdbcTemplate jdbc;
    public JdbcTransactionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Stored> find(String scope, String eventId) {
        var rows = jdbc.query("""
                SELECT t.request_hash, jsonb_build_object('transaction', t.input_json, 'evaluation',
                    jsonb_build_object('id', e.id, 'eventId', e.event_id, 'policyHash', e.policy_hash,
                        'buildId', e.build_id, 'decision', e.decision, 'coverage', e.coverage,
                        'acceptedAt', to_char(t.accepted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                        'evaluatedAt', to_char(e.evaluated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                        'contextHighWatermark', e.context_high_watermark,
                        'ruleResults', (SELECT jsonb_agg(jsonb_build_object('ruleId', r.rule_id,
                            'algorithmVersion', r.algorithm_version, 'status', r.status, 'reasonCode', r.reason_code,
                            'evidence', r.evidence) ORDER BY r.rule_id)
                            FROM fraud.rule_results r WHERE r.scope=t.scope AND r.event_id=t.event_id)))::text AS resource
                FROM fraud.transactions t JOIN fraud.evaluations e ON e.scope=t.scope AND e.event_id=t.event_id
                WHERE t.scope=? AND t.event_id=?
                """, (row, index) -> new Stored(row.getString("request_hash"), row.getString("resource")), scope, eventId);
        return rows.stream().findFirst();
    }

    @Override public void timeouts(int statementMillis, int lockMillis) {
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)",
                (row, index) -> true, statementMillis + "ms", lockMillis + "ms");
    }
    @Override public void createCustomer(String scope, String customerId) {
        jdbc.update("INSERT INTO fraud.customer_processing_state(scope,customer_id) VALUES (?,?) ON CONFLICT DO NOTHING", scope, customerId);
    }
    @Override public State lockCustomer(String scope, String customerId) {
        return jdbc.queryForObject("SELECT last_accepted_at,customer_sequence FROM fraud.customer_processing_state WHERE scope=? AND customer_id=? FOR UPDATE",
                (row,index) -> new State(row.getObject("last_accepted_at", OffsetDateTime.class) == null ? null
                        : row.getObject("last_accepted_at", OffsetDateTime.class).toInstant(), row.getLong("customer_sequence")), scope, customerId);
    }
    @Override public String bindInstrument(String scope, String instrumentId, String customerId) {
        jdbc.update("INSERT INTO fraud.instrument_owners(scope,instrument_id,customer_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", scope,instrumentId,customerId);
        return jdbc.queryForObject("SELECT customer_id FROM fraud.instrument_owners WHERE scope=? AND instrument_id=?",String.class,scope,instrumentId);
    }
    static String historySql(HistoryQuery query) {
        return switch (query) {
            case HistoryQuery.Count ignored -> """
                    SELECT count(*) FROM fraud.transactions
                    WHERE scope=? AND customer_id=? AND accepted_at>? AND accepted_at<=? AND customer_sequence<=?
                    """;
            case HistoryQuery.Spend ignored -> """
                    SELECT coalesce(sum(amount),0) FROM fraud.transactions
                    WHERE scope=? AND customer_id=? AND accepted_at>? AND accepted_at<=? AND customer_sequence<=? AND currency=?
                    """;
        };
    }
    static Object[] historyArguments(String scope, String customerId, HistoryQuery query, Instant acceptedAt, long highWatermark) {
        var start = utc(acceptedAt.minusSeconds(query.windowSeconds()));
        return switch (query) {
            case HistoryQuery.Count ignored -> new Object[]{scope,customerId,start,utc(acceptedAt),highWatermark};
            case HistoryQuery.Spend spend -> new Object[]{scope,customerId,start,utc(acceptedAt),highWatermark,spend.currency()};
        };
    }
    @Override public BigDecimal aggregate(String scope, String customerId, HistoryQuery query, Instant acceptedAt, long highWatermark) {
        return jdbc.queryForObject(historySql(query), BigDecimal.class,
                historyArguments(scope,customerId,query,acceptedAt,highWatermark));
    }

    @Override public Instant databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }
    @Override public void insertEvent(Caller caller, TransactionEvent event, String hash, Instant acceptedAt, long sequence) {
        var input = new LinkedHashMap<>(CanonicalTransaction.document(caller.scope(), event));
        input.remove("scope"); input.remove("canonicalizationVersion");
        if (event.physicalLocation() == null) input.remove("physicalLocation");
        else {
            var location = new LinkedHashMap<String,Object>();
            location.put("latitude", event.physicalLocation().latitude()); location.put("longitude", event.physicalLocation().longitude());
            location.put("accuracyKm", event.physicalLocation().accuracyKm()); location.put("source", event.physicalLocation().source().name());
            if (event.physicalLocation().city() != null) location.put("city", event.physicalLocation().city());
            input.put("physicalLocation", location);
        }
        var location = event.physicalLocation();
        jdbc.update("""
                INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,
                    transaction_type,status,channel,amount,currency,category,occurred_at,accepted_at,customer_sequence,
                    producer_id,source_permission_version,source_permissions,latitude,longitude,accuracy_km,location_source,city,input_json)
                VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb)
                """, caller.scope(),event.eventId(),event.customerId(),event.instrumentId(),hash,event.transactionType().name(),
                event.status().name(),event.channel().name(),event.money().amount(),event.money().currency(),event.category(),
                utc(event.occurredAt()),utc(acceptedAt),sequence,caller.principalId(),caller.permissionVersion(),
                CanonicalJson.encode(caller.locationSources().stream().map(Enum::name).sorted().toList()),
                location==null?null:location.latitude(),location==null?null:location.longitude(),location==null?null:location.accuracyKm(),
                location==null?null:location.source().name(),location==null?null:location.city(),CanonicalJson.encode(input));
    }
    @Override public void insertEvaluation(String scope,String eventId,UUID id,String policyHash,String buildId,
            long highWatermark,Instant evaluatedAt,EvaluationOutcome outcome) {
        jdbc.update("""
                INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,decision,coverage,evaluated_at,expected_rule_count)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,scope,eventId,id,policyHash,buildId,highWatermark,outcome.decision().name(),outcome.coverage().name(),utc(evaluatedAt),outcome.ruleResults().size());
        for (var result:outcome.ruleResults()) {
            var facts = new LinkedHashMap<String,Object>();
            result.evidence().forEach((key,value) -> facts.put(key,switch(value) {
                case RuleResult.Text text -> text.value(); case RuleResult.Number number -> number.value(); case RuleResult.Flag flag -> flag.value();
            }));
            jdbc.update("INSERT INTO fraud.rule_results(scope,event_id,rule_id,algorithm_version,status,reason_code,evidence) VALUES (?,?,?,?,?,?,?::jsonb)",
                    scope,eventId,result.ruleId().value(),result.algorithmVersion(),result.status().name(),result.reasonCode(),CanonicalJson.encode(facts));
        }
    }
    @Override public void advance(String scope,String customerId,Instant acceptedAt,long sequence) {
        jdbc.update("UPDATE fraud.customer_processing_state SET last_accepted_at=?,customer_sequence=? WHERE scope=? AND customer_id=?",
                utc(acceptedAt),sequence,scope,customerId);
    }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
