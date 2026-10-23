package za.co.sibusiso.fraud.policy;

import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.evaluation.rules.PreparedPolicy;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class ActivePolicyProvider {
    private record Cached(long revision, PreparedPolicy policy) {}

    private final ActivePolicyStore store;
    private final PolicyLoader loader;
    private final Function<PolicySnapshot, PreparedPolicy> preparation;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public ActivePolicyProvider(
            ActivePolicyStore store,
            PolicyLoader loader,
            Function<PolicySnapshot, PreparedPolicy> preparation) {
        this.store = store;
        this.loader = loader;
        this.preparation = preparation;
    }

    public PolicySnapshot current() {
        return prepared().snapshot();
    }

    /** The database reference is read for every selection; a cached policy never selects itself. */
    public PreparedPolicy prepared() {
        try {
            var reference = store.active();
            Cached existing = cached.get();
            if (existing != null
                    && existing.policy().snapshot().policyHash().equals(reference.policyHash())) {
                if (reference.revision() > existing.revision()) {
                    cached.compareAndSet(
                            existing, new Cached(reference.revision(), existing.policy()));
                }
                return existing.policy();
            }
            PolicySnapshot selected = loader.read(store.document(reference.policyHash()));
            ContractChecks.require(
                    selected.policyHash().equals(reference.policyHash()),
                    "Stored policy hash does not match its content/build");
            PreparedPolicy prepared = preparation.apply(selected);
            cached.updateAndGet(
                    previous ->
                            previous == null || reference.revision() >= previous.revision()
                                    ? new Cached(reference.revision(), prepared)
                                    : previous);
            return prepared;
        } catch (RuntimeException failure) {
            throw new PolicyUnavailableException(failure);
        }
    }

    public void verifyActivePolicy() {
        current();
    }
}
