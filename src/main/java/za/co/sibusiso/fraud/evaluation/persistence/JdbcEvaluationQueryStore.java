package za.co.sibusiso.fraud.evaluation.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import za.co.sibusiso.fraud.evaluation.application.EvaluationQuery;
import za.co.sibusiso.fraud.evaluation.application.EvaluationQueryStore;

@Repository
@Profile("!migrate & !policy-admin")
public class JdbcEvaluationQueryStore implements EvaluationQueryStore {
    private final JdbcTemplate jdbc;
    public JdbcEvaluationQueryStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();}
    @Override public void timeouts(int statementMillis,int lockMillis){
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)",
                (row,index)->true,statementMillis+"ms",lockMillis+"ms");
    }
    static String keysSql(EvaluationQuery.Window window) {
        return "SELECT scope,event_id,id,accepted_at FROM fraud.evaluations WHERE scope=? AND accepted_at>=? AND accepted_at<?"
                +(window.customerId()==null?"":" AND customer_id=?")+(window.decision()==null?"":" AND decision=?")
                +(window.after()==null?"":" AND (accepted_at,id)>(?,?)")+" ORDER BY accepted_at,id LIMIT ?";
    }
    static Object[] arguments(String scope,EvaluationQuery.Window window){
        var values=new ArrayList<Object>(List.of(scope,utc(window.from()),utc(window.to())));
        if(window.customerId()!=null)values.add(window.customerId());
        if(window.decision()!=null)values.add(window.decision().name());
        if(window.after()!=null){values.add(utc(window.after().acceptedAt()));values.add(window.after().id());}
        values.add(Math.addExact(window.pageSize(),1));return values.toArray();
    }
    @Override public List<Row> page(String scope,EvaluationQuery.Window window){
        String sql="""
                WITH page AS MATERIALIZED (%s)
                SELECT e.accepted_at,e.id,(%s)::text AS evaluation FROM page p
                JOIN fraud.evaluations e ON e.scope=p.scope AND e.event_id=p.event_id
                JOIN fraud.transactions t ON t.scope=e.scope AND t.event_id=e.event_id
                ORDER BY p.accepted_at,p.id
                """.formatted(keysSql(window),EvaluationSql.DOCUMENT);
        return jdbc.query(sql,(row,index)->new Row(new EvaluationQuery.Position(row.getObject("accepted_at",OffsetDateTime.class).toInstant(),
                row.getObject("id",UUID.class)),row.getString("evaluation")),arguments(scope,window));
    }
    private static OffsetDateTime utc(Instant value){return value.atOffset(ZoneOffset.UTC);}
}
