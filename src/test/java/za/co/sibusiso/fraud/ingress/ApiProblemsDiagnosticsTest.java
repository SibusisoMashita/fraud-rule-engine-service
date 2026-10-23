package za.co.sibusiso.fraud.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Field;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Reason;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ApiProblems;
import za.co.sibusiso.fraud.ingress.error.ValidationError;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.ingress.security.CredentialAuthenticator;
import za.co.sibusiso.fraud.ingress.security.IngressFilter;
import za.co.sibusiso.fraud.ingress.security.IngressRoute;
import za.co.sibusiso.fraud.ingress.security.SharedRateLimiter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

class ApiProblemsDiagnosticsTest {
    private static final String SECRET = "sensitive-api-key";
    private static final String PAYLOAD = "sensitive-customer-and-transaction";
    private static final String SQL = "SELECT sensitive_column FROM financial_data";
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiProblems.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopCapturing() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void serverFailureLinksSafeCauseLocationsToTheResponseAndLogsOnlyOnce() throws Exception {
        var original = new SQLException(SQL + " " + SECRET + " " + PAYLOAD);
        original.setStackTrace(
                new StackTraceElement[] {
                    new StackTraceElement(
                            "org.postgresql.Driver", "receiveErrorResponse", "Driver.java", 42)
                });
        var database = new DataAccessResourceFailureException(SQL, original);
        var failure =
                new FlowFailure(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.COMMIT_OUTCOME_UNKNOWN,
                        true,
                        database);
        var request = sensitiveRequest();
        var response = new MockHttpServletResponse();
        var advice = new IngressAdvice();
        advice.flow(failure, request, response);

        assertThat(failure.getCause()).isSameAs(database);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("COMMIT_OUTCOME_UNKNOWN", "UNKNOWN")
                .doesNotContain("\"errors\"");
        String correlation = response.getHeader("X-Correlation-ID");
        assertThat(correlation).isNotBlank();
        assertThat(response.getContentAsString()).contains(correlation);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains(
                        "correlationId=" + correlation,
                        "code=COMMIT_OUTCOME_UNKNOWN",
                        "status=503",
                        "outcome=UNKNOWN",
                        SQLException.class.getName(),
                        "org.postgresql.Driver.receiveErrorResponse(Driver.java:42)");
        assertSafeLog(logs.list.getFirst());

        response.flushBuffer();
        advice.unreadable(new IOException(SECRET + PAYLOAD), request, response);
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void failuresAfterResponseCommitStillHaveDiagnosticsWithoutRewritingTheResponse()
            throws Exception {
        var advice = new IngressAdvice();
        for (boolean ioFailure : new boolean[] {true, false}) {
            var request = sensitiveRequest();
            request.setAttribute(IngressController.FINANCIAL_RESPONSE, true);
            var response = new MockHttpServletResponse();
            ApiProblems.prepare(request, response);
            String correlation = response.getHeader("X-Correlation-ID");
            response.getWriter().write("already sent");
            response.flushBuffer();
            if (ioFailure) {
                advice.unreadable(new IOException(SECRET + SQL + PAYLOAD), request, response);
            } else {
                advice.unexpected(
                        new IllegalStateException(SECRET + SQL + PAYLOAD), request, response);
            }
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("already sent");
            ILoggingEvent event = logs.list.getLast();
            assertThat(event.getFormattedMessage())
                    .contains(
                            "correlationId=" + correlation,
                            "responseCommitted=true",
                            "outcome=UNKNOWN",
                            ioFailure
                                    ? IOException.class.getName()
                                    : IllegalStateException.class.getName());
            assertSafeLog(event);
        }
        assertThat(logs.list).hasSize(2);
    }

    @Test
    void credentialDependencyFailureIsLoggedBeforeDispatchWithoutLeakingCredentials()
            throws Exception {
        var cause = new DataAccessResourceFailureException(SQL + SECRET + PAYLOAD);
        var authenticator = mock(CredentialAuthenticator.class);
        when(authenticator.authenticate(SECRET)).thenThrow(cause);
        var limiter = mock(SharedRateLimiter.class);
        var yaml = new StrictYaml();
        var limits =
                yaml.read(
                        yaml.readText(new ClassPathResource("config/service.example.yaml")),
                        ServiceLimits.class);
        var filter = new IngressFilter(authenticator, limiter, limits);
        var request = sensitiveRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (ignoredRequest, ignoredResponse) -> {
                    throw new AssertionError(
                            "Unavailable credentials must not reach the controller");
                });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("DEPENDENCY_UNAVAILABLE", "NOT_ACCEPTED");
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains(
                        "correlationId=" + response.getHeader("X-Correlation-ID"),
                        DataAccessResourceFailureException.class.getName());
        assertSafeLog(logs.list.getFirst());
        verifyNoInteractions(limiter);
    }

    @Test
    void redisFailureRetainsItsCauseAndOnlyLogsSafeDetails() throws Exception {
        var original = new DataAccessResourceFailureException(SECRET + SQL + PAYLOAD);
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(original);
        var yaml = new StrictYaml();
        var limits =
                yaml.read(
                        yaml.readText(new ClassPathResource("config/service.example.yaml")),
                        ServiceLimits.class);
        var caller = new Caller("producer", "scope", Set.of(Caller.Role.INGEST), Set.of(), 1);
        var failure =
                assertThrows(
                        ApiFailure.class,
                        () ->
                                new SharedRateLimiter(redis, limits)
                                        .acquire(caller, IngressRoute.INGEST));
        assertThat(failure.getCause()).isSameAs(original);
        assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failure.code()).isEqualTo("LIMITER_UNAVAILABLE");
        var response = new MockHttpServletResponse();
        new IngressAdvice().rejected(failure, sensitiveRequest(), response);
        assertThat(response.getContentAsString()).contains("NOT_ACCEPTED");
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
                .contains(
                        "correlationId=" + response.getHeader("X-Correlation-ID"),
                        DataAccessResourceFailureException.class.getName());
        assertSafeLog(logs.list.getFirst());
    }

    @Test
    void expectedClientRejectionsDoNotCreateServerFailureLogs() throws Exception {
        for (HttpStatus status :
                new HttpStatus[] {
                    HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                    HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_CONTENT,
                            HttpStatus.TOO_MANY_REQUESTS
                }) {
            var response = new MockHttpServletResponse();
            ApiProblems.write(
                    sensitiveRequest(), response, new ApiFailure(status, ErrorCode.FORBIDDEN));
            assertThat(response.getContentAsString()).doesNotContain("\"errors\"");
        }
        assertThat(logs.list).isEmpty();
    }

    @Test
    void fieldDetailsCannotClaimAnUnknownOrServerFailureIsInvalidInput() {
        var detail = new ValidationError(Field.OCCURRED_AT, Reason.INVALID_TIMESTAMP);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ApiFailure(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
                                .withValidationError(detail));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ApiFailure(
                                        HttpStatus.UNPROCESSABLE_CONTENT,
                                        ErrorCode.INVALID_TRANSACTION,
                                        0,
                                        ApiFailure.Outcome.UNKNOWN)
                                .withValidationError(detail));
    }

    private static MockHttpServletRequest sensitiveRequest() {
        var request = new MockHttpServletRequest("POST", "/api/v1/transactions");
        request.addHeader("X-API-Key", SECRET);
        request.setContent(PAYLOAD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setQueryString("customerId=" + PAYLOAD);
        return request;
    }

    private static void assertSafeLog(ILoggingEvent event) {
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .doesNotContain(SECRET, PAYLOAD, SQL, "/api/v1/transactions", "customerId=");
    }
}
