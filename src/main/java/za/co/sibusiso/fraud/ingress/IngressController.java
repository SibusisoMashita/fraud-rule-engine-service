package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import java.util.Collections;
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
    private final TransactionDecoder decoder;
    public IngressController(ServiceLimits limits) { decoder = new TransactionDecoder(limits); }

    @PostMapping("/api/v1/transactions")
    public void submit(HttpServletRequest request, @AuthenticationPrincipal Caller caller) throws IOException {
        if (request.getQueryString() != null) throw new ApiFailure(400, "UNSUPPORTED_QUERY");
        var event = decoder.decode(request.getInputStream(), request.getContentLengthLong(),
                singleHeader(request, "Content-Type"), singleHeader(request, "Content-Encoding"));
        if (event.physicalLocation() != null && !caller.locationSources().contains(event.physicalLocation().source())) {
            throw new ApiFailure(403, "LOCATION_SOURCE_FORBIDDEN");
        }
        ((RequestDeadline) request.getAttribute(RequestDeadline.ATTRIBUTE)).check();
        // No acceptance or persistence until the transactional evaluation flow is installed.
        throw new ApiFailure(503, "FEATURE_UNAVAILABLE");
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE");
        return values.isEmpty() ? null : values.getFirst();
    }

    @GetMapping({"/api/v1/transactions/{eventId}", "/api/v1/evaluations", "/api/v1/rules"})
    public void query() { throw new ApiFailure(503, "FEATURE_UNAVAILABLE"); }
}
