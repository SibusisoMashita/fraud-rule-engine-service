package za.co.sibusiso.fraud.ingress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ApiFailure.Outcome;
import za.co.sibusiso.fraud.ingress.error.ApiProblems;
import za.co.sibusiso.fraud.ingress.error.ValidationError;

import java.io.IOException;

@RestControllerAdvice(assignableTypes = {IngressController.class, PolicyManagementController.class})
@Profile("!migrate & !policy-admin")
public final class IngressAdvice {
    @ExceptionHandler(FlowFailure.class)
    public void flow(FlowFailure failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long retryAfterSeconds =
                failure.status() == HttpStatus.SERVICE_UNAVAILABLE
                        ? ApiFailure.DEFAULT_RETRY_AFTER_SECONDS
                        : 0;
        var problem =
                new ApiFailure(
                        failure.status(),
                        failure.code(),
                        retryAfterSeconds,
                        failure.unknown() ? Outcome.UNKNOWN : Outcome.NOT_ACCEPTED,
                        failure);
        if (failure.status() == HttpStatus.UNPROCESSABLE_CONTENT && !failure.unknown()) {
            var error = ValidationError.forEligibility(failure.code());
            if (error != null) problem = problem.withValidationError(error);
        }
        respond(request, response, problem);
    }

    @ExceptionHandler(Exception.class)
    public void unexpected(
            Exception failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        respond(
                request,
                response,
                new ApiFailure(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCode.INTERNAL_ERROR,
                        0,
                        financialOutcome(request),
                        failure));
    }

    @ExceptionHandler(ApiFailure.class)
    public void rejected(
            ApiFailure failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        respond(request, response, failure);
    }

    @ExceptionHandler(IOException.class)
    public void unreadable(
            IOException failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Outcome outcome = financialOutcome(request);
        if (response.isCommitted() || outcome == Outcome.UNKNOWN) {
            respond(
                    request,
                    response,
                    new ApiFailure(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            Boolean.TRUE.equals(
                                            request.getAttribute(
                                                    PolicyManagementController.POLICY_RESPONSE))
                                    ? ErrorCode.POLICY_OUTCOME_UNKNOWN
                                    : ErrorCode.RESPONSE_OUTCOME_UNKNOWN,
                            ApiFailure.DEFAULT_RETRY_AFTER_SECONDS,
                            outcome,
                            failure));
        } else {
            respond(
                    request,
                    response,
                    new ApiFailure(
                            HttpStatus.BAD_REQUEST,
                            ErrorCode.UNREADABLE_BODY,
                            0,
                            Outcome.NOT_ACCEPTED,
                            failure));
        }
    }

    private static void respond(
            HttpServletRequest request, HttpServletResponse response, ApiFailure failure)
            throws IOException {
        if (response.isCommitted()) {
            ApiProblems.logFailure(request, response, failure);
        } else {
            ApiProblems.write(request, response, failure);
        }
    }

    private static Outcome financialOutcome(HttpServletRequest request) {
        return (Boolean.TRUE.equals(request.getAttribute(IngressController.FINANCIAL_RESPONSE))
                        || Boolean.TRUE.equals(
                                request.getAttribute(PolicyManagementController.POLICY_RESPONSE)))
                ? Outcome.UNKNOWN
                : Outcome.NOT_ACCEPTED;
    }
}
