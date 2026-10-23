package za.co.sibusiso.fraud.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.Money;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.PolicyUnavailableException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

class ServiceFailureCauseTest {
    private static final Caller CALLER =
            new Caller(
                    "producer",
                    "scope",
                    Set.of(Caller.Role.INGEST, Caller.Role.READ, Caller.Role.POLICY),
                    Set.of(),
                    1);
    private static final TransactionEvent EVENT =
            new TransactionEvent(
                    "event",
                    "customer",
                    "card",
                    TransactionEvent.Type.CARD_PURCHASE,
                    TransactionEvent.Status.BOOKED,
                    TransactionEvent.Channel.ECOMMERCE,
                    Money.parse("ZAR", "1.00"),
                    "GROCERIES",
                    Instant.parse("2026-10-15T10:00:00Z"),
                    null);

    @ParameterizedTest
    @MethodSource("writeFailures")
    void transactionMappingRetainsTheOriginalCauseAndAcceptanceOutcome(
            RuntimeException original,
            boolean commitReady,
            HttpStatus status,
            ErrorCode code,
            boolean unknown) {
        var store = mock(TransactionStore.class);
        when(store.find("scope", "event")).thenReturn(Optional.empty());
        EvaluationWriter writer =
                (caller, event, hash, remaining, attempt) -> {
                    if (commitReady) attempt.readyToCommit();
                    throw original;
                };
        var service = new TransactionService(store, writer);
        assertThatThrownBy(() -> service.submit(CALLER, EVENT, () -> 1000))
                .isInstanceOfSatisfying(
                        FlowFailure.class,
                        failure -> {
                            assertThat(failure.getCause()).isSameAs(original);
                            assertThat(failure.status()).isEqualTo(status);
                            assertThat(failure.code()).isEqualTo(code.name());
                            assertThat(failure.unknown()).isEqualTo(unknown);
                        });
    }

    static Stream<Arguments> writeFailures() {
        return Stream.of(
                Arguments.of(
                        new DataAccessResourceFailureException("private database detail"),
                        false,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.DEPENDENCY_UNAVAILABLE,
                        false),
                Arguments.of(
                        new CannotCreateTransactionException("private database detail"),
                        false,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.DEPENDENCY_UNAVAILABLE,
                        false),
                Arguments.of(
                        new TransactionSystemException("private transaction detail"),
                        false,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.TRANSACTION_OUTCOME_UNKNOWN,
                        true),
                Arguments.of(
                        new PolicyUnavailableException(new IllegalStateException("private policy")),
                        false,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.DEPENDENCY_UNAVAILABLE,
                        false),
                Arguments.of(
                        new IllegalStateException("private rule detail"),
                        false,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCode.EVALUATION_FAILED,
                        false),
                Arguments.of(
                        new DuplicateKeyException("private database detail"),
                        false,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.CONCURRENT_WRITE_RETRY,
                        false),
                Arguments.of(
                        new IllegalStateException("private rule detail"),
                        true,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                        true),
                Arguments.of(
                        new DuplicateKeyException("private database detail"),
                        true,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                        true));
    }

    @Test
    void readMappingsRetainTheOriginalDependencyFailure() {
        var original = new DataAccessResourceFailureException("private SQL and row detail");
        var store = mock(TransactionStore.class);
        when(store.find("scope", "event")).thenThrow(original);
        assertDependencyCause(
                () ->
                        new TransactionService(store, mock(EvaluationWriter.class))
                                .get(CALLER, "event", () -> 1000),
                original);

        var reader = mock(EvaluationQueryReader.class);
        when(reader.list(eq("scope"), any(), any())).thenThrow(original);
        var queryService = new EvaluationQueryService(reader);
        assertDependencyCause(
                () ->
                        queryService.list(
                                CALLER,
                                new EvaluationQuery.Request(null, null, null, null, null, null),
                                () -> 1000),
                original);
        when(reader.rules(any())).thenThrow(original);
        assertDependencyCause(() -> queryService.rules(CALLER, () -> 1000), original);
    }

    private static void assertDependencyCause(Runnable operation, Throwable original) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        FlowFailure.class,
                        failure -> {
                            assertThat(failure.getCause()).isSameAs(original);
                            assertThat(failure.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                            assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(failure.unknown()).isFalse();
                        });
    }
}
