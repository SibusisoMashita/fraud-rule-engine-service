package za.co.sibusiso.fraud.ingress.error;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

public final class ApiProblems {
    private static final Logger LOG = LoggerFactory.getLogger(ApiProblems.class);
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final String FAILURE_LOGGED = ApiProblems.class.getName() + ".failureLogged";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CORRELATION = ApiProblems.class.getName();
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String AUTHENTICATION_SCHEME = "ApiKey";

    private ApiProblems() {}

    public static void prepare(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(CORRELATION_HEADER, correlationId(request));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    /**
     * The coordinator supplies UNKNOWN when a commit/response failure cannot prove nonacceptance.
     */
    public static void write(
            HttpServletRequest request, HttpServletResponse response, ApiFailure failure)
            throws IOException {
        prepare(request, response);
        logFailure(request, response, failure);
        response.setStatus(failure.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (failure.status() == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, AUTHENTICATION_SCHEME);
        }
        if (failure.retryAfterSeconds() > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()));
        }
        JSON.writeValue(
                response.getOutputStream(),
                Map.of(
                        "type", "about:blank",
                        "title", failure.status().getReasonPhrase(),
                        "status", failure.status().value(),
                        "code", failure.code(),
                        "correlationId", request.getAttribute(CORRELATION),
                        "retryable", failure.retryAfterSeconds() > 0,
                        "outcome", failure.outcome()));
    }

    /** Log once, even when a response is already committed. Never render exception messages. */
    public static void logFailure(
            HttpServletRequest request, HttpServletResponse response, ApiFailure failure) {
        if (!failure.status().is5xxServerError()
                || Boolean.TRUE.equals(request.getAttribute(FAILURE_LOGGED))) {
            return;
        }
        request.setAttribute(FAILURE_LOGGED, true);
        LOG.error(
                "request_failure correlationId={} code={} status={} outcome={} responseCommitted={} failures={}",
                correlationId(request),
                failure.code(),
                failure.status().value(),
                failure.outcome(),
                response.isCommitted(),
                safeFailures(failure));
    }

    private static String correlationId(HttpServletRequest request) {
        if (request.getAttribute(CORRELATION) == null) {
            request.setAttribute(CORRELATION, UUID.randomUUID().toString());
        }
        return (String) request.getAttribute(CORRELATION);
    }

    private static List<Map<String, Object>> safeFailures(Throwable failure) {
        var failures = new ArrayList<Map<String, Object>>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure;
                cause != null && failures.size() < MAX_CAUSE_DEPTH && seen.add(cause);
                cause = cause.getCause()) {
            StackTraceElement[] stack = cause.getStackTrace();
            String location = stack.length == 0 ? "unavailable" : stack[0].toString();
            failures.add(Map.of("type", cause.getClass().getName(), "location", location));
        }
        return failures;
    }
}
