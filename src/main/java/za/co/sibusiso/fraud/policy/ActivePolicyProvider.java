package za.co.sibusiso.fraud.policy;

import java.util.concurrent.atomic.AtomicReference;
import za.co.sibusiso.fraud.configuration.PolicyLoader;
import za.co.sibusiso.fraud.configuration.PolicySnapshot;
import za.co.sibusiso.fraud.domain.ContractChecks;

public final class ActivePolicyProvider {
    private record Cached(long revision, PolicySnapshot policy) { }
    private final ActivePolicyStore store;
    private final PolicyLoader loader;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public ActivePolicyProvider(ActivePolicyStore store, PolicyLoader loader) {
        this.store = store;
        this.loader = loader;
    }

    /** The database reference is read for every selection; a cached version never selects itself. */
    public PolicySnapshot current() {
        try {
            var reference = store.active();
            Cached existing = cached.get();
            if (existing != null && existing.policy().policyHash().equals(reference.policyHash())) return existing.policy();
            PolicySnapshot selected = loader.read(store.document(reference.policyHash()));
            ContractChecks.require(selected.policyHash().equals(reference.policyHash()), "Stored policy hash does not match its content/build");
            cached.updateAndGet(previous -> previous == null || reference.revision() >= previous.revision()
                    ? new Cached(reference.revision(), selected) : previous);
            return selected;
        } catch (RuntimeException failure) {
            throw new PolicyUnavailableException(failure);
        }
    }

    public void verifyActivePolicy() { current(); }
}
