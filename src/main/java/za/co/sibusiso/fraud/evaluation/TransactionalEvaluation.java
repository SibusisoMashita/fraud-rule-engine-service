package za.co.sibusiso.fraud.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.ingress.Caller;
import za.co.sibusiso.fraud.policy.ActivePolicyProvider;

@Service
@Profile("!migrate & !policy-admin")
public class TransactionalEvaluation implements EvaluationWriter {
    private final TransactionStore store;
    private final ActivePolicyProvider policies;
    private final RuleEngine engine;
    private final ServiceLimits limits;
    private final EvaluatorBuild build;
    public TransactionalEvaluation(TransactionStore store, ActivePolicyProvider policies, RuleEngine engine, ServiceLimits limits, EvaluatorBuild build) {
        this.store=store; this.policies=policies; this.engine=engine; this.limits=limits; this.build=build;
    }

    @Override
    @Transactional(isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class, timeoutString="${fraud.transaction-timeout-seconds}")
    public Submission write(Caller caller, TransactionEvent event, String hash, LongSupplier remaining, Attempt attempt) throws Exception {
        var budget = new WorkBudget(remaining,limits.operations());
        change(budget,()->store.createCustomer(caller.scope(),event.customerId()));
        var state = sql(budget,()->store.lockCustomer(caller.scope(),event.customerId()));
        var replay = sql(budget,()->store.find(caller.scope(),event.eventId()));
        if (replay.isPresent()) {
            var result = TransactionService.compare(replay.orElseThrow(),hash);
            prepareCommit(budget,attempt); return new Submission(false,result);
        }
        String owner=sql(budget,()->store.bindInstrument(caller.scope(),event.instrumentId(),event.customerId()));
        if (!owner.equals(event.customerId())) throw new FlowFailure(409,"INSTRUMENT_OWNER_CONFLICT");
        Instant wallTime=EventTime.validate(sql(budget,store::databaseTime));
        Instant acceptedAt=wallTime;
        if (state.lastAcceptedAt()!=null && state.lastAcceptedAt().isAfter(wallTime)) {
            if (Duration.between(wallTime,state.lastAcceptedAt()).compareTo(Duration.ofMillis(limits.operations().maxClockRegressionMillis()))>0)
                throw new FlowFailure(503,"CLOCK_REGRESSION");
            acceptedAt=state.lastAcceptedAt();
        }
        var policy=sql(budget,policies::current);
        var eligibility=policy.ingest().checkNewEvent(event,policy.currencies(),wallTime);
        if (eligibility!=TransactionEligibility.ELIGIBLE) throw new FlowFailure(422,eligibility.name());
        long sequence=Math.addExact(state.sequence(),1);
        Instant accepted=acceptedAt;
        change(budget,()->store.insertEvent(caller,event,hash,accepted,sequence));
        var facts = new LinkedHashMap<HistoryQuery, BigDecimal>();
        for (var query : engine.historyQueries(event, policy)) {
            facts.put(query, sql(budget, () -> store.aggregate(caller.scope(), event.customerId(), query, accepted, state.sequence())));
        }
        var locations = new LinkedHashMap<LocationQuery, LocationCandidates>();
        for (var query : engine.locationQueries(event, policy)) {
            locations.put(query, sql(budget, () -> store.locations(caller.scope(), event.customerId(), query, state.sequence())));
        }
        var outcome=engine.evaluate(event,policy,new FraudRule.Context(accepted,state.sequence(),new HistoryFacts(facts,locations)),limits.api());
        budget.remaining();
        Instant evaluated=sql(budget,store::databaseTime);
        Instant evaluatedAt=evaluated.isBefore(accepted)?accepted:evaluated;
        change(budget,()->store.insertEvaluation(caller.scope(),event.eventId(),UUID.randomUUID(),policy.policyHash(),build.id(),state.sequence(),evaluatedAt,outcome));
        change(budget,()->store.advance(caller.scope(),event.customerId(),accepted,sequence));
        var result=sql(budget,()->store.find(caller.scope(),event.eventId())).orElseThrow();
        prepareCommit(budget,attempt);
        return new Submission(true,result);
    }
    private void prepareCommit(WorkBudget budget,Attempt attempt) {
        int timeout=budget.commitMillis();
        store.timeouts(timeout,Math.min(timeout,limits.operations().lockTimeoutMillis()));
        attempt.readyToCommit();
    }
    private <T> T sql(WorkBudget budget,Supplier<T> action) {
        store.timeouts(budget.statementMillis(),budget.lockMillis()); return action.get();
    }
    private void change(WorkBudget budget,Runnable action) { sql(budget,()->{action.run();return null;}); }
}
