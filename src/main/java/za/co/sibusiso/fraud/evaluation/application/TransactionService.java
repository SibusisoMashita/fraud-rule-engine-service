package za.co.sibusiso.fraud.evaluation.application;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;

import za.co.sibusiso.fraud.contracts.CanonicalTransaction;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.PolicyUnavailableException;

import java.util.function.LongSupplier;

/** Nontransactional coordinator; recovery queries run only after the write proxy finishes. */
@Service
@Profile("!migrate & !policy-admin")
public class TransactionService {
    private final TransactionStore store;
    private final EvaluationWriter writer;

    public TransactionService(TransactionStore store, EvaluationWriter writer) {
        this.store = store;
        this.writer = writer;
    }

    public EvaluationWriter.Submission submit(
            Caller caller, TransactionEvent event, LongSupplier remaining) {
        authorize(caller, Caller.Role.INGEST);
        if (event.physicalLocation() != null
                && !caller.locationSources().contains(event.physicalLocation().source()))
            throw new FlowFailure(HttpStatus.FORBIDDEN, ErrorCode.LOCATION_SOURCE_FORBIDDEN);
        String hash = CanonicalTransaction.hash(caller.scope(), event);
        var attempt = new EvaluationWriter.Attempt();
        try {
            check(remaining);
            var existing = store.find(caller.scope(), event.eventId());
            if (existing.isPresent())
                return new EvaluationWriter.Submission(
                        false, compare(existing.orElseThrow(), hash));
            check(remaining);
            return writer.write(caller, event, hash, remaining, attempt);
        } catch (FlowFailure known) {
            throw known;
        } catch (DuplicateKeyException race) {
            if (attempt.isCommitReady())
                throw new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                        true,
                        race);
            try {
                check(remaining);
                var winner = store.find(caller.scope(), event.eventId());
                if (winner.isPresent())
                    return new EvaluationWriter.Submission(
                            false, compare(winner.orElseThrow(), hash));
                throw new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CONCURRENT_WRITE_RETRY, race);
            } catch (DataAccessException unavailable) {
                throw new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.DEPENDENCY_UNAVAILABLE,
                        unavailable);
            }
        } catch (Exception failure) {
            if (attempt.isCommitReady())
                throw new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                        true,
                        failure);
            if (failure instanceof TransactionSystemException)
                throw new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.TRANSACTION_OUTCOME_UNKNOWN,
                        true,
                        failure);
            if (failure instanceof DataAccessException
                    || failure instanceof TransactionException
                    || failure instanceof PolicyUnavailableException)
                throw new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE, failure);
            throw new FlowFailure(
                    HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.EVALUATION_FAILED, failure);
        }
    }

    public TransactionStore.Stored get(Caller caller, String eventId, LongSupplier remaining) {
        authorize(caller, Caller.Role.READ);
        ContractChecks.identifier(eventId);
        try {
            check(remaining);
            var result =
                    store.find(caller.scope(), eventId)
                            .orElseThrow(
                                    () ->
                                            new FlowFailure(
                                                    HttpStatus.NOT_FOUND,
                                                    ErrorCode.TRANSACTION_NOT_FOUND));
            check(remaining);
            return result;
        } catch (DataAccessException unavailable) {
            throw new FlowFailure(
                    HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    static TransactionStore.Stored compare(TransactionStore.Stored stored, String hash) {
        if (!stored.requestHash().equals(hash))
            throw new FlowFailure(HttpStatus.CONFLICT, ErrorCode.EVENT_CONTENT_CONFLICT);
        return stored;
    }

    private static void check(LongSupplier remaining) {
        if (remaining.getAsLong() <= 0)
            throw new FlowFailure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEADLINE_EXCEEDED);
    }

    private static void authorize(Caller caller, Caller.Role role) {
        if (!caller.roles().contains(role))
            throw new FlowFailure(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    }
}
