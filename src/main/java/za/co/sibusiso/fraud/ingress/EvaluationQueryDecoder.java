package za.co.sibusiso.fraud.ingress;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EventTime;
import za.co.sibusiso.fraud.domain.EvaluationOutcome.Decision;
import za.co.sibusiso.fraud.evaluation.EvaluationQuery;

public final class EvaluationQueryDecoder {
    private static final Set<String> FIELDS=Set.of("customerId","decision","acceptedFrom","acceptedTo","pageSize","cursor");
    private final ServiceLimits.Api limits;
    public EvaluationQueryDecoder(ServiceLimits.Api limits){this.limits=limits;}
    public EvaluationQuery.Request decode(Map<String,String[]> parameters){
        try{
            if(!FIELDS.containsAll(parameters.keySet()))throw invalid();
            for(var values:parameters.values())if(values.length!=1 || values[0]==null || values[0].isEmpty())throw invalid();
            String customer=value(parameters,"customerId"),decision=value(parameters,"decision"),cursor=value(parameters,"cursor"),size=value(parameters,"pageSize");
            if(customer!=null){ContractChecks.identifier(customer);if(customer.length()>limits.maxIdentifierLength())throw invalid();}
            if(cursor!=null && cursor.length()>limits.maxCursorLength())throw invalid();
            Integer pageSize=null;
            if(size!=null){
                if(!size.matches("-?(?:0|[1-9][0-9]*)"))throw invalid();
                if(size.length()>10)throw new ApiFailure(422,"QUERY_LIMIT_EXCEEDED");
                try{pageSize=Integer.valueOf(size);}catch(NumberFormatException overflow){throw new ApiFailure(422,"QUERY_LIMIT_EXCEEDED");}
                if(pageSize<1 || pageSize>limits.maxPageSize())throw new ApiFailure(422,"QUERY_LIMIT_EXCEEDED");
            }
            return new EvaluationQuery.Request(customer,decision==null?null:Decision.valueOf(decision),time(parameters,"acceptedFrom"),
                    time(parameters,"acceptedTo"),pageSize,cursor);
        }catch(ApiFailure known){throw known;}catch(RuntimeException invalid){throw invalid();}
    }
    private Instant time(Map<String,String[]> parameters,String key){String value=value(parameters,key);return value==null?null:EventTime.parse(value);}
    private String value(Map<String,String[]> parameters,String key){return parameters.containsKey(key)?parameters.get(key)[0]:null;}
    private ApiFailure invalid(){return new ApiFailure(400,"INVALID_QUERY");}
}
