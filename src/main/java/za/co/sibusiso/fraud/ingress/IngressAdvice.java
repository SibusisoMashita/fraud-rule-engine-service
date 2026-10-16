package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.ingress.error.ApiFailure.Outcome;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ApiProblems;

@RestControllerAdvice(assignableTypes = IngressController.class)
@Profile("!migrate & !policy-admin")
public final class IngressAdvice {
    @ExceptionHandler(FlowFailure.class)
    public void flow(FlowFailure failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long retryAfterSeconds =
                failure.status() == HttpStatus.SERVICE_UNAVAILABLE
                        ? ApiFailure.DEFAULT_RETRY_AFTER_SECONDS
                        : 0;
        ApiProblems.write(
                request,
                response,
                new ApiFailure(
                        failure.status(),
                        failure.code(),
                        retryAfterSeconds,
                        failure.unknown() ? Outcome.UNKNOWN : Outcome.NOT_ACCEPTED));
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!response.isCommitted()) {
            ApiProblems.write(
                    request,
                    response,
                    new ApiFailure(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            ErrorCode.INTERNAL_ERROR,
                            0,
                            financialOutcome(request)));
        }
    }

    @ExceptionHandler(ApiFailure.class)
    public void rejected(
            ApiFailure failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ApiProblems.write(request, response, failure);
    }

    @ExceptionHandler(IOException.class)
    public void unreadable(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (response.isCommitted()) return;
        if (financialOutcome(request) == Outcome.UNKNOWN) {
            ApiProblems.write(
                    request,
                    response,
                    new ApiFailure(
                            HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.RESPONSE_OUTCOME_UNKNOWN,
                            ApiFailure.DEFAULT_RETRY_AFTER_SECONDS, Outcome.UNKNOWN));
        } else {
            ApiProblems.write(
                    request,
                    response,
                    new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.UNREADABLE_BODY));
        }
    }

    private static Outcome financialOutcome(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(IngressController.FINANCIAL_RESPONSE))
                ? Outcome.UNKNOWN
                : Outcome.NOT_ACCEPTED;
    }
}
