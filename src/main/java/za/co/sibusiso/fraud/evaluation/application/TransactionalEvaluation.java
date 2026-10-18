package za.co.sibusiso.fraud.evaluation.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.EventTime;
import za.co.sibusiso.fraud.domain.TransactionEligibility;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.EvaluatorBuild;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.evaluation.history.HistoryFacts;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.history.LocationCandidates;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsHistory;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;
import za.co.sibusiso.fraud.evaluation.rules.FraudRule;
import za.co.sibusiso.fraud.evaluation.rules.RuleEngine;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.ActivePolicyProvider;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;

@Service
@Profile("!migrate & !policy-admin")
public class TransactionalEvaluation implements EvaluationWriter {
    private final TransactionStore store;
    private final ActivePolicyProvider policies;
    private final RuleEngine engine;
    private final ServiceLimits limits;
    private final EvaluatorBuild build;

    public TransactionalEvaluation(
            TransactionStore store,
            ActivePolicyProvider policies,
            RuleEngine engine,
            ServiceLimits limits,
            EvaluatorBuild build) {
        this.store = store;
        this.policies = policies;
        this.engine = engine;
        this.limits = limits;
        this.build = build;
    }

    @Override
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class,
            timeoutString = "${fraud.transaction-timeout-seconds}")
    public Submission write(
            Caller caller,
            TransactionEvent event,
            String hash,
            LongSupplier remaining,
            Attempt attempt)
            throws Exception {
        var budget = new WorkBudget(remaining, limits.operations());
        change(budget, () -> store.createCustomer(caller.scope(), event.customerId()));
        var state = sql(budget, () -> store.lockCustomer(caller.scope(), event.customerId()));
        var replay = sql(budget, () -> store.find(caller.scope(), event.eventId()));
        if (replay.isPresent()) {
            var result = TransactionService.compare(replay.orElseThrow(), hash);
            prepareCommit(budget, attempt);
            return new Submission(false, result);
        }

        String owner =
                sql(
                        budget,
                        () ->
                                store.bindInstrument(
                                        caller.scope(), event.instrumentId(), event.customerId()));
        if (!owner.equals(event.customerId())) {
            throw new FlowFailure(HttpStatus.CONFLICT, ErrorCode.INSTRUMENT_OWNER_CONFLICT);
        }
        Instant wallTime = EventTime.validate(sql(budget, store::databaseTime));
        Instant acceptedAt = acceptanceTime(wallTime, state);
        var policy = sql(budget, policies::current);
        var eligibility = policy.ingest().checkNewEvent(event, policy.currencies(), wallTime);
        if (eligibility != TransactionEligibility.ELIGIBLE) {
            throw new FlowFailure(HttpStatus.UNPROCESSABLE_CONTENT, eligibility.name());
        }

        long sequence = Math.addExact(state.sequence(), 1);
        change(budget, () -> store.insertEvent(caller, event, hash, acceptedAt, sequence));
        var history = loadHistory(caller, event, policy, acceptedAt, state.sequence(), budget);
        var context = new FraudRule.Context(acceptedAt, state.sequence(), history);
        var outcome = engine.evaluate(event, policy, context, limits.api());
        budget.remaining();

        Instant evaluated = sql(budget, store::databaseTime);
        Instant evaluatedAt = evaluated.isBefore(acceptedAt) ? acceptedAt : evaluated;
        change(
                budget,
                () ->
                        store.insertEvaluation(
                                caller.scope(),
                                event.eventId(),
                                UUID.randomUUID(),
                                policy.policyHash(),
                                build.id(),
                                state.sequence(),
                                evaluatedAt,
                                outcome));
        change(
                budget,
                () -> store.advance(caller.scope(), event.customerId(), acceptedAt, sequence));
        var result = sql(budget, () -> store.find(caller.scope(), event.eventId())).orElseThrow();
        prepareCommit(budget, attempt);
        return new Submission(true, result);
    }

    private Instant acceptanceTime(Instant wallTime, TransactionStore.State state) {
        Instant previous = state.lastAcceptedAt();
        if (previous == null || !previous.isAfter(wallTime)) {
            return wallTime;
        }
        Duration regression = Duration.between(wallTime, previous);
        Duration tolerance = Duration.ofMillis(limits.operations().maxClockRegressionMillis());
        if (regression.compareTo(tolerance) > 0) {
            throw new FlowFailure(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CLOCK_REGRESSION);
        }
        return previous;
    }

    private HistoryFacts loadHistory(
            Caller caller,
            TransactionEvent event,
            PolicySnapshot policy,
            Instant acceptedAt,
            long highWatermark,
            WorkBudget budget) {
        var facts = new LinkedHashMap<HistoryQuery, BigDecimal>();
        for (var query : engine.historyQueries(event, policy)) {
            facts.put(
                    query,
                    sql(
                            budget,
                            () ->
                                    store.aggregate(
                                            caller.scope(),
                                            event.customerId(),
                                            query,
                                            acceptedAt,
                                            highWatermark)));
        }
        var locations = new LinkedHashMap<LocationQuery, LocationCandidates>();
        for (var query : engine.locationQueries(event, policy)) {
            locations.put(
                    query,
                    sql(
                            budget,
                            () ->
                                    store.locations(
                                            caller.scope(),
                                            event.customerId(),
                                            query,
                                            highWatermark)));
        }
        var smallPayments = new LinkedHashMap<SmallPaymentsQuery, SmallPaymentsHistory>();
        for (var query : engine.smallPaymentsQueries(event, policy)) {
            smallPayments.put(
                    query,
                    sql(
                            budget,
                            () ->
                                    store.smallPayments(
                                            caller.scope(),
                                            event.customerId(),
                                            query,
                                            highWatermark)));
        }
        return new HistoryFacts(facts, locations, smallPayments);
    }

    private void prepareCommit(WorkBudget budget, Attempt attempt) {
        int timeout = budget.commitMillis();
        store.timeouts(timeout, Math.min(timeout, limits.operations().lockTimeoutMillis()));
        attempt.readyToCommit();
    }

    private <T> T sql(WorkBudget budget, Supplier<T> action) {
        store.timeouts(budget.statementMillis(), budget.lockMillis());
        return action.get();
    }

    private void change(WorkBudget budget, Runnable action) {
        sql(
                budget,
                () -> {
                    action.run();
                    return null;
                });
    }
}
