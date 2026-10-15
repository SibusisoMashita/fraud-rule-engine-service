package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

public final class ApiProblems {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CORRELATION = ApiProblems.class.getName();
    private ApiProblems() { }

    public static void prepare(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(CORRELATION) == null) request.setAttribute(CORRELATION, UUID.randomUUID().toString());
        response.setHeader("X-Correlation-ID", (String) request.getAttribute(CORRELATION));
        response.setHeader("Cache-Control", "no-store");
    }

    /** Admission errors precede any financial write, so NOT_ACCEPTED is known here. */
    public static void write(HttpServletRequest request, HttpServletResponse response, ApiFailure failure) throws IOException {
        prepare(request, response);
        response.setStatus(failure.status());
        response.setContentType("application/problem+json");
        if (failure.status() == 401) response.setHeader("WWW-Authenticate", "ApiKey");
        if (failure.retryAfterSeconds() > 0) response.setHeader("Retry-After", Long.toString(failure.retryAfterSeconds()));
        JSON.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", HttpStatus.valueOf(failure.status()).getReasonPhrase(),
                "status", failure.status(), "code", failure.code(), "correlationId", request.getAttribute(CORRELATION),
                "retryable", failure.retryAfterSeconds() > 0, "outcome", "NOT_ACCEPTED"));
    }
}
