package za.co.sibusiso.fraud.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import static org.assertj.core.api.Assertions.*;

class ActivePolicyProviderTest {
    private final za.co.sibusiso.fraud.evaluation.rules.RuleEngine engine = new za.co.sibusiso.fraud.evaluation.rules.RuleEngine(
            java.util.List.of(new za.co.sibusiso.fraud.evaluation.rules.HighAmountRule(), new za.co.sibusiso.fraud.evaluation.rules.TransactionVelocityRule(), new za.co.sibusiso.fraud.evaluation.rules.CumulativeSpendRule(),new za.co.sibusiso.fraud.evaluation.rules.SmallPaymentBurstRule(), new za.co.sibusiso.fraud.evaluation.rules.ImpossibleTravelRule(new StrictYaml().read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class).api().maxLocationCandidatesPerEvaluation())),
            new StrictYaml().read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class).api().maxEvidenceFactsPerRule());
    private final StrictYaml yaml = new StrictYaml();
    private final PolicyLoader loader = new PolicyLoader(yaml, RuleRegistry.of(BuiltInRules.definitions(), 64));
    private final String original = yaml.readText(new ClassPathResource("config/policy.example.yaml"));

    @Test
    void selectsCommittedDatabaseVersionOnEveryCallWithoutRestartOrPolling() {
        var store = new Store();
        String firstHash = store.publish(1, original);
        var provider = new ActivePolicyProvider(store, loader, engine::verify);
        var first = provider.current();
        assertThat(provider.current()).isSameAs(first);
        assertThat(store.documentReads).hasValue(1);
        String secondHash = store.publish(2, original.replace("25000.00", "25000.01"));
        assertThat(provider.current().policyHash()).isEqualTo(secondHash).isNotEqualTo(firstHash);
        assertThat(first.policyHash()).isEqualTo(firstHash);
        assertThat(store.referenceReads).hasValue(3);
        assertThat(store.documentReads).hasValue(2);
    }

    @Test
    void unavailableDatabaseOrInvalidNewVersionCannotFallBackToCachedPolicy() {
        var store = new Store();
        String hash = store.publish(1, original);
        var provider = new ActivePolicyProvider(store, loader, engine::verify);
        provider.current();
        store.unavailable = true;
        assertThatThrownBy(provider::current).isInstanceOf(PolicyUnavailableException.class);
        store.unavailable = false;
        store.documents.put("0".repeat(64), "schemaVersion: 999");
        store.reference.set(new ActivePolicyStore.Reference(2, "0".repeat(64)));
        assertThatThrownBy(provider::current).isInstanceOf(PolicyUnavailableException.class);
        store.reference.set(new ActivePolicyStore.Reference(3, hash));
        assertThat(provider.current().policyHash()).isEqualTo(hash);
    }

    @Test
    void mismatchedHashOrIncompatibleBuildIsRejected() {
        var store = new Store();
        store.documents.put("f".repeat(64), original);
        store.reference.set(new ActivePolicyStore.Reference(1, "f".repeat(64)));
        assertThatThrownBy(new ActivePolicyProvider(store, loader, engine::verify)::current)
                .isInstanceOf(PolicyUnavailableException.class).rootCause().hasMessageContaining("hash");
    }

    @Test
    void overlappingSelectionsKeepWholeSnapshotsAndCannotRegressTheCache() throws Exception {
        var store = new Store();
        String firstHash = store.publish(1, original);
        var selectedOld = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        ActivePolicyStore blocking = new ActivePolicyStore() {
            public Reference active() { return store.active(); }
            public String document(String hash) {
                if (hash.equals(firstHash)) {
                    selectedOld.countDown();
                    try {
                        if (!releaseOld.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return store.document(hash);
            }
        };
        var provider = new ActivePolicyProvider(blocking, loader, engine::verify);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldCall = executor.submit(provider::current);
            try {
                assertThat(selectedOld.await(5, TimeUnit.SECONDS)).isTrue();
                String latest = store.publish(2, original.replace("25000.00", "25000.01"));
                var next = provider.current();
                assertThat(next.policyHash()).isEqualTo(latest);
                releaseOld.countDown();
                assertThat(oldCall.get(5, TimeUnit.SECONDS).policyHash()).isEqualTo(firstHash);
                assertThat(provider.current()).isSameAs(next);
                assertThat(store.documentReads).hasValue(2);
            } finally { releaseOld.countDown(); }
        }
    }

    private final class Store implements ActivePolicyStore {
        final Map<String, String> documents = new ConcurrentHashMap<>();
        final AtomicReference<Reference> reference = new AtomicReference<>();
        final AtomicInteger referenceReads = new AtomicInteger();
        final AtomicInteger documentReads = new AtomicInteger();
        volatile boolean unavailable;

        String publish(long revision, String text) {
            String hash = loader.read(text).policyHash();
            documents.put(hash, text);
            reference.set(new Reference(revision, hash));
            return hash;
        }

        public Reference active() {
            referenceReads.incrementAndGet();
            if (unavailable) throw new IllegalStateException("Synthetic database outage");
            return reference.get();
        }

        public String document(String hash) {
            documentReads.incrementAndGet();
            return documents.get(hash);
        }
    }
}
