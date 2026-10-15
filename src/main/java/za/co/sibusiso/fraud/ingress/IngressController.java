package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import java.util.Collections;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import za.co.sibusiso.fraud.evaluation.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sibusiso.fraud.configuration.ServiceLimits;

@RestController
@Profile("!migrate & !policy-admin")
public final class IngressController {
    static final String FINANCIAL_RESPONSE = IngressController.class.getName() + ".financialResponse";
    private final TransactionDecoder decoder;
    private final TransactionService transactions;
    private final int identifierLimit;
    public IngressController(ServiceLimits limits, TransactionService transactions) {
        decoder = new TransactionDecoder(limits); this.transactions = transactions;
        identifierLimit = limits.api().maxIdentifierLength();
    }

    @PostMapping("/api/v1/transactions")
    public ResponseEntity<String> submit(HttpServletRequest request, @AuthenticationPrincipal Caller caller) throws IOException {
        if (request.getQueryString() != null) throw new ApiFailure(400, "UNSUPPORTED_QUERY");
        var event = decoder.decode(request.getInputStream(), request.getContentLengthLong(),
                singleHeader(request, "Content-Type"), singleHeader(request, "Content-Encoding"));
        if (event.physicalLocation() != null && !caller.locationSources().contains(event.physicalLocation().source())) {
            throw new ApiFailure(403, "LOCATION_SOURCE_FORBIDDEN");
        }
        ((RequestDeadline) request.getAttribute(RequestDeadline.ATTRIBUTE)).check();
        var deadline = (RequestDeadline) request.getAttribute(RequestDeadline.ATTRIBUTE);
        request.setAttribute(FINANCIAL_RESPONSE, true);
        var result = transactions.submit(caller, event, deadline::remainingMillis);
        return ResponseEntity.status(result.created() ? 201 : 200).contentType(MediaType.APPLICATION_JSON)
                .location(URI.create("/api/v1/transactions/" + event.eventId())).body(result.stored().json());
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
        return values.isEmpty() ? null : values.getFirst();
    }

    @GetMapping("/api/v1/transactions/{eventId}")
    public ResponseEntity<String> get(@PathVariable String eventId, @AuthenticationPrincipal Caller caller, HttpServletRequest request) {
        if (eventId.length() > identifierLimit) throw new ApiFailure(400, "INVALID_FIELD");
        if (request.getQueryString() != null) throw new ApiFailure(400, "UNSUPPORTED_QUERY");
        var deadline = (RequestDeadline) request.getAttribute(RequestDeadline.ATTRIBUTE);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(transactions.get(caller, eventId, deadline::remainingMillis).json());
    }

    @GetMapping({"/api/v1/evaluations", "/api/v1/rules"})
    public void query() { throw new ApiFailure(503, "FEATURE_UNAVAILABLE"); }
}
