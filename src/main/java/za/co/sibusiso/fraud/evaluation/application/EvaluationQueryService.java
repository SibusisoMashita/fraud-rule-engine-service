package za.co.sibusiso.fraud.evaluation.application;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.PolicyUnavailableException;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Catch read transaction completion failures outside the proxy; reads have no financial effect. */
@Service
@Profile("!migrate & !policy-admin")
public final class EvaluationQueryService {
    private final EvaluationQueryReader reader;

    public EvaluationQueryService(EvaluationQueryReader reader) {
        this.reader = reader;
    }

    public String list(Caller caller, EvaluationQuery.Request query, LongSupplier remaining) {
        authorize(caller, Caller.Role.READ);
        return read(() -> reader.list(caller.scope(), query, remaining));
    }

    public String rules(Caller caller, LongSupplier remaining) {
        authorize(caller, Caller.Role.POLICY);
        return read(() -> reader.rules(remaining));
    }

    private String read(Supplier<String> work) {
        try {
            return work.get();
        } catch (DataAccessException
                | TransactionException
                | PolicyUnavailableException unavailable) {
            throw new FlowFailure(
                    HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private void authorize(Caller caller, Caller.Role role) {
        if (!caller.roles().contains(role))
            throw new FlowFailure(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    }
}
