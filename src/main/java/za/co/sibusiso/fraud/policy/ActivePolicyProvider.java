package za.co.sibusiso.fraud.policy;

import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;

import java.util.concurrent.atomic.AtomicReference;

public final class ActivePolicyProvider {
    private record Cached(long revision, PolicySnapshot policy) {}

    private final ActivePolicyStore store;
    private final PolicyLoader loader;
    private final java.util.function.Consumer<PolicySnapshot> compatibility;
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    public ActivePolicyProvider(
            ActivePolicyStore store,
            PolicyLoader loader,
            java.util.function.Consumer<PolicySnapshot> compatibility) {
        this.store = store;
        this.loader = loader;
        this.compatibility = compatibility;
    }

    /**
     * The database reference is read for every selection; a cached version never selects itself.
     */
    public PolicySnapshot current() {
        try {
            var reference = store.active();
            Cached existing = cached.get();
            if (existing != null && existing.policy().policyHash().equals(reference.policyHash()))
                return existing.policy();
            PolicySnapshot selected = loader.read(store.document(reference.policyHash()));
            ContractChecks.require(
                    selected.policyHash().equals(reference.policyHash()),
                    "Stored policy hash does not match its content/build");
            compatibility.accept(selected);
            cached.updateAndGet(
                    previous ->
                            previous == null || reference.revision() >= previous.revision()
                                    ? new Cached(reference.revision(), selected)
                                    : previous);
            return selected;
        } catch (RuntimeException failure) {
            throw new PolicyUnavailableException(failure);
        }
    }

    public void verifyActivePolicy() {
        current();
    }
}
