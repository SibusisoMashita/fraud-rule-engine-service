package za.co.sibusiso.fraud.ingress.request;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EvaluationOutcome.Decision;
import za.co.sibusiso.fraud.domain.EventTime;
import za.co.sibusiso.fraud.evaluation.EvaluationQuery;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

public final class EvaluationQueryDecoder {
    private static final Set<String> FIELDS =
            Set.of("customerId", "decision", "acceptedFrom", "acceptedTo", "pageSize", "cursor");
    private static final int MAX_INTEGER_DIGITS = 10;

    private final ServiceLimits.Api limits;

    public EvaluationQueryDecoder(ServiceLimits.Api limits) {
        this.limits = limits;
    }

    public EvaluationQuery.Request decode(Map<String, String[]> parameters) {
        try {
            if (!FIELDS.containsAll(parameters.keySet())) {
                throw invalid();
            }
            for (var values : parameters.values()) {
                if (values.length != 1 || values[0] == null || values[0].isEmpty()) {
                    throw invalid();
                }
            }
            String customer = value(parameters, "customerId");
            String decision = value(parameters, "decision");
            String cursor = value(parameters, "cursor");
            String size = value(parameters, "pageSize");
            if (customer != null) {
                ContractChecks.identifier(customer);
                if (customer.length() > limits.maxIdentifierLength()) {
                    throw invalid();
                }
            }
            if (cursor != null && cursor.length() > limits.maxCursorLength()) {
                throw invalid();
            }
            Integer pageSize = parsePageSize(size);
            return new EvaluationQuery.Request(
                    customer,
                    decision == null ? null : Decision.valueOf(decision),
                    time(parameters, "acceptedFrom"),
                    time(parameters, "acceptedTo"),
                    pageSize,
                    cursor);
        } catch (ApiFailure known) {
            throw known;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private Integer parsePageSize(String size) {
        if (size == null) {
            return null;
        }
        if (!size.matches("-?(?:0|[1-9][0-9]*)")) {
            throw invalid();
        }
        if (size.length() > MAX_INTEGER_DIGITS) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.QUERY_LIMIT_EXCEEDED);
        }
        int pageSize;
        try {
            pageSize = Integer.valueOf(size);
        } catch (NumberFormatException overflow) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.QUERY_LIMIT_EXCEEDED);
        }
        if (pageSize < 1 || pageSize > limits.maxPageSize()) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.QUERY_LIMIT_EXCEEDED);
        }
        return pageSize;
    }

    private Instant time(Map<String, String[]> parameters, String key) {
        String value = value(parameters, key);
        return value == null ? null : EventTime.parse(value);
    }

    private String value(Map<String, String[]> parameters, String key) {
        return parameters.containsKey(key) ? parameters.get(key)[0] : null;
    }

    private ApiFailure invalid() {
        return new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_QUERY);
    }
}
