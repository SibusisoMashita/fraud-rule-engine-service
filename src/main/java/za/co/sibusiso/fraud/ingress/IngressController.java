package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.evaluation.EvaluationQueryService;
import za.co.sibusiso.fraud.evaluation.TransactionService;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.request.EvaluationQueryDecoder;
import za.co.sibusiso.fraud.ingress.request.RequestDeadline;
import za.co.sibusiso.fraud.ingress.request.TransactionDecoder;
import za.co.sibusiso.fraud.ingress.security.Caller;

@RestController
@Profile("!migrate & !policy-admin")
public final class IngressController {
    static final String FINANCIAL_RESPONSE =
            IngressController.class.getName() + ".financialResponse";

    private final TransactionDecoder decoder;
    private final TransactionService transactions;
    private final int identifierLimit;
    private final EvaluationQueryService queries;
    private final EvaluationQueryDecoder queryDecoder;

    public IngressController(
            ServiceLimits limits, TransactionService transactions, EvaluationQueryService queries) {
        this.decoder = new TransactionDecoder(limits);
        this.transactions = transactions;
        this.identifierLimit = limits.api().maxIdentifierLength();
        this.queries = queries;
        this.queryDecoder = new EvaluationQueryDecoder(limits.api());
    }

    @PostMapping(ApiPaths.TRANSACTIONS)
    public ResponseEntity<String> submit(
            HttpServletRequest request, @AuthenticationPrincipal Caller caller) throws IOException {
        rejectQueryString(request);
        var event =
                decoder.decode(
                        request.getInputStream(),
                        request.getContentLengthLong(),
                        singleHeader(request, HttpHeaders.CONTENT_TYPE),
                        singleHeader(request, HttpHeaders.CONTENT_ENCODING));
        if (event.physicalLocation() != null
                && !caller.locationSources().contains(event.physicalLocation().source())) {
            throw new ApiFailure(HttpStatus.FORBIDDEN, ErrorCode.LOCATION_SOURCE_FORBIDDEN);
        }
        var deadline = deadline(request);
        deadline.check();
        request.setAttribute(FINANCIAL_RESPONSE, true);
        var result = transactions.submit(caller, event, deadline::remainingMillis);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .location(URI.create(ApiPaths.TRANSACTIONS + "/" + event.eventId()))
                .body(result.stored().json());
    }

    @GetMapping(ApiPaths.TRANSACTIONS + "/{eventId}")
    public ResponseEntity<String> get(
            @PathVariable String eventId,
            @AuthenticationPrincipal Caller caller,
            HttpServletRequest request) {
        if (eventId.length() > identifierLimit) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
        }
        rejectQueryString(request);
        var deadline = deadline(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(transactions.get(caller, eventId, deadline::remainingMillis).json());
    }

    @GetMapping(ApiPaths.EVALUATIONS)
    public ResponseEntity<String> evaluations(
            HttpServletRequest request, @AuthenticationPrincipal Caller caller) {
        var query = queryDecoder.decode(request.getParameterMap());
        var deadline = deadline(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(queries.list(caller, query, deadline::remainingMillis));
    }

    @GetMapping(ApiPaths.RULES)
    public ResponseEntity<String> rules(
            HttpServletRequest request, @AuthenticationPrincipal Caller caller) {
        rejectQueryString(request);
        var deadline = deadline(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(queries.rules(caller, deadline::remainingMillis));
    }

    private static void rejectQueryString(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.UNSUPPORTED_QUERY);
        }
    }

    private static RequestDeadline deadline(HttpServletRequest request) {
        return (RequestDeadline) request.getAttribute(RequestDeadline.ATTRIBUTE);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            throw new ApiFailure(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        return values.isEmpty() ? null : values.getFirst();
    }
}
