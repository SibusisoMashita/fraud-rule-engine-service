package za.co.sibusiso.fraud.policy.management;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.evaluation.rules.CumulativeSpendRule;
import za.co.sibusiso.fraud.evaluation.rules.HighAmountRule;
import za.co.sibusiso.fraud.evaluation.rules.ImpossibleTravelRule;
import za.co.sibusiso.fraud.evaluation.rules.RuleEngine;
import za.co.sibusiso.fraud.evaluation.rules.SmallPaymentBurstRule;
import za.co.sibusiso.fraud.evaluation.rules.TransactionVelocityRule;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

class PolicyManagementServiceTest {
    private final StrictYaml yaml = new StrictYaml();
    private final String original =
            yaml.readText(new ClassPathResource("config/policy.example.yaml"));
    private final PolicyLoader loader =
            new PolicyLoader(yaml, RuleRegistry.of(BuiltInRules.definitions(), 64));
    private final JdbcTemplate runtime = mock(JdbcTemplate.class);
    private final JdbcPolicyWriter writer = mock(JdbcPolicyWriter.class);
    private final RuleEngine engine =
            new RuleEngine(
                    List.of(
                            new HighAmountRule(),
                            new TransactionVelocityRule(),
                            new CumulativeSpendRule(),
                            new ImpossibleTravelRule(2000),
                            new SmallPaymentBurstRule()),
                    16);
    private final PolicyManagementService service =
            new PolicyManagementService(runtime, writer, yaml, loader, engine);
    private final Caller admin =
            new Caller(
                    "administrator",
                    "global-policy",
                    Set.of(Caller.Role.POLICY_ADMIN),
                    Set.of(),
                    1);

    @Test
    void validationUsesTheRuleEngineWithoutTouchingEitherDatabaseConnection() {
        var document = document(original.replace("25000.00", "10000.00"));
        assertThat(service.validate(admin, document, () -> {}).policyHash())
                .isEqualTo(loader.read(document).policyHash());
        verifyNoInteractions(runtime, writer);
    }

    @Test
    void anInvalidOrUnsupportedPolicyCannotReachTheWriter() {
        var invalid = document(original.replace("enabled: true", "enabled: false"));
        assertThatThrownBy(() -> service.activate(admin, invalid, 1, () -> {}))
                .isInstanceOfSatisfying(
                        ApiFailure.class,
                        failure -> {
                            assertThat(failure.status())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                            assertThat(failure.code()).isEqualTo("POLICY_INVALID");
                            assertThat(failure.outcome()).isEqualTo("NOT_ACCEPTED");
                        });
        var incompatible =
                new PolicyManagementService(
                        runtime, writer, yaml, loader, new RuleEngine(List.of(), 16));
        assertThatThrownBy(() -> incompatible.validate(admin, document(original), () -> {}))
                .isInstanceOfSatisfying(
                        ApiFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("POLICY_INVALID"));
        verifyNoInteractions(runtime, writer);
    }

    @Test
    void inspectionPermissionDoesNotGrantAdministrationEvenOutsideHttp() {
        var reader = new Caller("reader", "scope", Set.of(Caller.Role.POLICY), Set.of(), 1);
        assertThatThrownBy(() -> service.current(reader, () -> {})).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> service.validate(reader, null, () -> {}))
                .isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> service.activate(reader, null, 1, () -> {}))
                .isInstanceOf(ApiFailure.class);
        verifyNoInteractions(runtime, writer);
    }

    @Test
    void expiredBudgetPreventsActivationBeforeBorrowingTheWriter() {
        var expired =
                new ApiFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        za.co.sibusiso.fraud.contracts.ErrorCode.DEADLINE_EXCEEDED);
        assertThatThrownBy(
                        () ->
                                service.activate(
                                        admin,
                                        document(original),
                                        1,
                                        () -> {
                                            throw expired;
                                        }))
                .isSameAs(expired);
        verifyNoInteractions(runtime, writer);
    }

    @Test
    void lostDatabaseAcknowledgementPreservesItsCauseAndReportsAnUnknownOutcome() {
        var cause =
                new DataAccessResourceFailureException(
                        "private database diagnostic", new SQLException("private", "08006"));
        when(writer.activate(anyString(), anyString(), anyLong(), anyString())).thenThrow(cause);
        assertThatThrownBy(() -> service.activate(admin, document(original), 1, () -> {}))
                .isInstanceOfSatisfying(
                        ApiFailure.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(failure.code()).isEqualTo("POLICY_OUTCOME_UNKNOWN");
                            assertThat(failure.outcome()).isEqualTo("UNKNOWN");
                            assertThat(failure.getCause()).isSameAs(cause);
                        });
        verify(writer).activate(anyString(), anyString(), eq(1L), eq(admin.principalId()));
    }

    @Test
    void databaseRevisionRejectionIsDefinitiveAndKeepsItsCause() {
        var cause =
                new DataAccessResourceFailureException(
                        "stale", new SQLException("revision changed", "P0412"));
        when(writer.activate(anyString(), anyString(), anyLong(), anyString())).thenThrow(cause);
        assertThatThrownBy(() -> service.activate(admin, document(original), 1, () -> {}))
                .isInstanceOfSatisfying(
                        ApiFailure.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
                            assertThat(failure.outcome()).isEqualTo("NOT_ACCEPTED");
                            assertThat(failure.getCause()).isSameAs(cause);
                        });
    }

    private PolicyLoader.Document document(String text) {
        return yaml.read(text, PolicyLoader.Document.class);
    }
}
