package za.co.sibusiso.fraud.ingress.error;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

public final class ApiProblems {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CORRELATION = ApiProblems.class.getName();
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String AUTHENTICATION_SCHEME = "ApiKey";

    private ApiProblems() {}

    public static void prepare(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(CORRELATION) == null) {
            request.setAttribute(CORRELATION, UUID.randomUUID().toString());
        }
        response.setHeader(CORRELATION_HEADER, (String) request.getAttribute(CORRELATION));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    /**
     * The coordinator supplies UNKNOWN when a commit/response failure cannot prove nonacceptance.
     */
    public static void write(
            HttpServletRequest request, HttpServletResponse response, ApiFailure failure)
            throws IOException {
        prepare(request, response);
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
}
