package za.co.sibusiso.fraud.policy;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.evaluation.rules.PreparedPolicy;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ActivePolicyProviderTest {
    private final za.co.sibusiso.fraud.evaluation.rules.RuleEngine engine =
            new za.co.sibusiso.fraud.evaluation.rules.RuleEngine(
                    java.util.List.of(
                            new za.co.sibusiso.fraud.evaluation.rules.HighAmountRule(),
                            new za.co.sibusiso.fraud.evaluation.rules.TransactionVelocityRule(),
                            new za.co.sibusiso.fraud.evaluation.rules.CumulativeSpendRule(),
                            new za.co.sibusiso.fraud.evaluation.rules.SmallPaymentBurstRule(),
                            new za.co.sibusiso.fraud.evaluation.rules.ImpossibleTravelRule(
                                    new StrictYaml()
                                            .read(
                                                    new ClassPathResource(
                                                            "config/service.example.yaml"),
                                                    ServiceLimits.class)
                                            .api()
                                            .maxLocationCandidatesPerEvaluation())),
                    new StrictYaml()
                            .read(
                                    new ClassPathResource("config/service.example.yaml"),
                                    ServiceLimits.class)
                            .api()
                            .maxEvidenceFactsPerRule());
    private final StrictYaml yaml = new StrictYaml();
    private final PolicyLoader loader =
            new PolicyLoader(yaml, RuleRegistry.of(BuiltInRules.definitions(), 64));
    private final String original =
            yaml.readText(new ClassPathResource("config/policy.example.yaml"));

    @Test
    void selectsCommittedDatabaseVersionOnEveryCallWithoutRestartOrPolling() {
        var store = new Store();
        String firstHash = store.publish(1, original);
        var preparations = new AtomicInteger();
        var provider =
                new ActivePolicyProvider(
                        store,
                        loader,
                        snapshot -> {
                            preparations.incrementAndGet();
                            return engine.prepare(snapshot);
                        });
        var first = provider.prepared();
        assertThat(provider.prepared()).isSameAs(first);
        assertThat(provider.current()).isSameAs(first.snapshot());
        assertThat(store.documentReads).hasValue(1);
        assertThat(preparations).hasValue(1);
        String secondHash = store.publish(2, original.replace("25000.00", "25000.01"));
        assertThat(provider.prepared().snapshot().policyHash())
                .isEqualTo(secondHash)
                .isNotEqualTo(firstHash);
        assertThat(first.snapshot().policyHash()).isEqualTo(firstHash);
        assertThat(store.referenceReads).hasValue(4);
        assertThat(store.documentReads).hasValue(2);
        assertThat(preparations).hasValue(2);
    }

    @Test
    void returningToAnEvictedPolicyPreparesItAgainInsteadOfGrowingTheCache() {
        var store = new Store();
        String firstHash = store.publish(1, original);
        var preparations = new AtomicInteger();
        var provider =
                new ActivePolicyProvider(
                        store,
                        loader,
                        snapshot -> {
                            preparations.incrementAndGet();
                            return engine.prepare(snapshot);
                        });
        var first = provider.prepared();
        String secondHash = store.publish(2, original.replace("25000.00", "25000.01"));
        assertThat(provider.prepared().snapshot().policyHash()).isEqualTo(secondHash);
        store.publish(3, original);
        var returned = provider.prepared();
        assertThat(returned).isNotSameAs(first);
        assertThat(returned.snapshot().policyHash()).isEqualTo(firstHash);
        assertThat(provider.prepared()).isSameAs(returned);
        assertThat(store.referenceReads).hasValue(4);
        assertThat(store.documentReads).hasValue(3);
        assertThat(preparations).hasValue(3);
    }

    @Test
    void unavailableDatabaseOrInvalidNewVersionCannotFallBackToCachedPolicy() {
        var store = new Store();
        String hash = store.publish(1, original);
        var provider = new ActivePolicyProvider(store, loader, engine::prepare);
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
    void mismatchedStoredHashIsRejected() {
        var store = new Store();
        store.documents.put("f".repeat(64), original);
        store.reference.set(new ActivePolicyStore.Reference(1, "f".repeat(64)));
        assertThatThrownBy(new ActivePolicyProvider(store, loader, engine::prepare)::current)
                .isInstanceOf(PolicyUnavailableException.class)
                .rootCause()
                .hasMessageContaining("hash");
    }

    @Test
    void anIncompatibleNewPolicyNeverFallsBackToTheCachedCompatiblePolicy() {
        var store = new Store();
        store.publish(1, original);
        var provider = new ActivePolicyProvider(store, loader, engine::prepare);
        var first = provider.prepared();
        int ceiling =
                yaml.read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class)
                        .api()
                        .maxLocationCandidatesPerEvaluation();
        store.publish(2, original.replace("maxCandidates: 500", "maxCandidates: " + (ceiling + 1)));
        assertThatThrownBy(provider::prepared)
                .isInstanceOf(PolicyUnavailableException.class)
                .rootCause()
                .hasMessageContaining("safety ceiling");
        assertThatThrownBy(provider::current).isInstanceOf(PolicyUnavailableException.class);
        store.publish(3, original);
        assertThat(provider.prepared()).isSameAs(first);
        assertThat(store.documentReads).hasValue(3);
    }

    @Test
    void overlappingPreparationsKeepWholeSnapshotsAndCannotRegressTheCache() throws Exception {
        var store = new Store();
        String firstHash = store.publish(1, original);
        var selectedOld = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        var provider =
                new ActivePolicyProvider(
                        store,
                        loader,
                        snapshot -> {
                            preparations.incrementAndGet();
                            if (snapshot.policyHash().equals(firstHash)) {
                                selectedOld.countDown();
                                awaitRelease(releaseOld);
                            }
                            return engine.prepare(snapshot);
                        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldCall = executor.submit(provider::prepared);
            try {
                assertThat(selectedOld.await(5, TimeUnit.SECONDS)).isTrue();
                String latest = store.publish(2, original.replace("25000.00", "25000.01"));
                var next = provider.prepared();
                assertThat(next.snapshot().policyHash()).isEqualTo(latest);
                releaseOld.countDown();
                assertThat(oldCall.get(5, TimeUnit.SECONDS).snapshot().policyHash())
                        .isEqualTo(firstHash);
                assertThat(provider.prepared()).isSameAs(next);
                assertThat(store.documentReads).hasValue(2);
                assertThat(store.referenceReads).hasValue(3);
                assertThat(preparations).hasValue(2);
            } finally {
                releaseOld.countDown();
            }
        }
    }

    @Test
    void concurrentMissesMayPrepareIndependentlyWithoutExposingPartialPolicies() throws Exception {
        var store = new Store();
        String hash = store.publish(1, original);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        var provider =
                new ActivePolicyProvider(
                        store,
                        loader,
                        snapshot -> {
                            preparations.incrementAndGet();
                            entered.countDown();
                            awaitRelease(release);
                            return engine.prepare(snapshot);
                        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCall = executor.submit(provider::prepared);
            var secondCall = executor.submit(provider::prepared);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                PreparedPolicy first = firstCall.get(5, TimeUnit.SECONDS);
                PreparedPolicy second = secondCall.get(5, TimeUnit.SECONDS);
                assertThat(first).isNotSameAs(second);
                assertThat(first.snapshot().canonicalForm())
                        .isEqualTo(second.snapshot().canonicalForm());
                assertThat(first.snapshot().policyHash()).isEqualTo(hash);
                assertThat(provider.prepared()).isIn(first, second);
                assertThat(store.referenceReads).hasValue(3);
                assertThat(store.documentReads).hasValue(2);
                assertThat(preparations).hasValue(2);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aNewerRevisionOfTheCachedHashPreventsAnOlderPreparationFromEvictingIt() throws Exception {
        var store = new Store();
        String firstHash = store.publish(1, original);
        String secondHash = loader.read(original.replace("25000.00", "25000.01")).policyHash();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        var provider =
                new ActivePolicyProvider(
                        store,
                        loader,
                        snapshot -> {
                            preparations.incrementAndGet();
                            if (snapshot.policyHash().equals(secondHash)) {
                                entered.countDown();
                                awaitRelease(release);
                            }
                            return engine.prepare(snapshot);
                        });
        var first = provider.prepared();
        store.publish(2, original.replace("25000.00", "25000.01"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldCall = executor.submit(provider::prepared);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                store.publish(3, original);
                assertThat(provider.prepared()).isSameAs(first);
                release.countDown();
                assertThat(oldCall.get(5, TimeUnit.SECONDS).snapshot().policyHash())
                        .isEqualTo(secondHash);
                assertThat(provider.prepared()).isSameAs(first);
                assertThat(first.snapshot().policyHash()).isEqualTo(firstHash);
                assertThat(store.referenceReads).hasValue(4);
                assertThat(store.documentReads).hasValue(2);
                assertThat(preparations).hasValue(2);
            } finally {
                release.countDown();
            }
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test latch timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
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
