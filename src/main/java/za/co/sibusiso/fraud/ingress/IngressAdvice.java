package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IngressController.class)
@Profile("!migrate & !policy-admin")
public final class IngressAdvice {
    @ExceptionHandler(za.co.sibusiso.fraud.evaluation.FlowFailure.class)
    public void flow(za.co.sibusiso.fraud.evaluation.FlowFailure failure, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiProblems.write(request, response, new ApiFailure(failure.status(), failure.code(), failure.status()==503 ? 1 : 0,
                failure.unknown() ? "UNKNOWN" : "NOT_ACCEPTED"));
    }
    @ExceptionHandler(Exception.class)
    public void unexpected(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) ApiProblems.write(request, response, new ApiFailure(500, "INTERNAL_ERROR", 0,
                Boolean.TRUE.equals(request.getAttribute(IngressController.FINANCIAL_RESPONSE)) ? "UNKNOWN" : "NOT_ACCEPTED"));
    }
    @ExceptionHandler(ApiFailure.class)
    public void rejected(ApiFailure failure, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiProblems.write(request, response, failure);
    }
    @ExceptionHandler(IOException.class)
    public void unreadable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        if (Boolean.TRUE.equals(request.getAttribute(IngressController.FINANCIAL_RESPONSE))) {
            ApiProblems.write(request, response, new ApiFailure(503, "RESPONSE_OUTCOME_UNKNOWN", 1, "UNKNOWN"));
        } else ApiProblems.write(request, response, new ApiFailure(400, "UNREADABLE_BODY"));
    }
}
