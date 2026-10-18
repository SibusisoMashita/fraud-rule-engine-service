package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import java.util.Collections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.request.PolicyDocumentDecoder;
import za.co.sibusiso.fraud.ingress.request.RequestDeadline;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.policy.management.PolicyManagementService;

@RestController
@Profile("!migrate & !policy-admin")
@ConditionalOnProperty(name = "fraud.policy-management.enabled", havingValue = "true")
public final class PolicyManagementController {
    static final String POLICY_RESPONSE = PolicyManagementController.class.getName() + ".policyResponse";
    private static final String ETAG_PREFIX = "\"policy-revision-";
    private final PolicyManagementService service;
    private final PolicyDocumentDecoder decoder;

    public PolicyManagementController(PolicyManagementService service, ServiceLimits limits, StrictYaml yaml) {
        this.service = service;
        decoder = new PolicyDocumentDecoder(limits, yaml);
    }

    @GetMapping(ApiPaths.ADMIN_POLICY)
    public ResponseEntity<PolicyManagementService.Active> current(HttpServletRequest request,
            @AuthenticationPrincipal Caller caller) {
        rejectQuery(request);
        return active(service.current(caller, deadline(request)::check));
    }

    @PostMapping(ApiPaths.ADMIN_POLICY_VALIDATE)
    public ResponseEntity<PolicyManagementService.Validation> validate(HttpServletRequest request,
            @AuthenticationPrincipal Caller caller) throws IOException {
        rejectQuery(request);
        var document = decoder.decode(request.getInputStream(), request.getContentLengthLong(),
                single(request, HttpHeaders.CONTENT_TYPE), single(request, HttpHeaders.CONTENT_ENCODING));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(service.validate(caller, document, deadline(request)::check));
    }

    @PutMapping(ApiPaths.ADMIN_POLICY)
    public ResponseEntity<PolicyManagementService.Active> activate(HttpServletRequest request,
            @AuthenticationPrincipal Caller caller) throws IOException {
        rejectQuery(request);
        long revision = revision(request);
        var document = decoder.decode(request.getInputStream(), request.getContentLengthLong(),
                single(request, HttpHeaders.CONTENT_TYPE), single(request, HttpHeaders.CONTENT_ENCODING));
        request.setAttribute(POLICY_RESPONSE, true);
        return active(service.activate(caller, document, revision, deadline(request)::check));
    }

    private static ResponseEntity<PolicyManagementService.Active> active(PolicyManagementService.Active value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .eTag(ETAG_PREFIX + value.revision() + "\"").body(value);
    }

    private static long revision(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(HttpHeaders.IF_MATCH));
        if (values.isEmpty())
            throw new ApiFailure(HttpStatus.PRECONDITION_REQUIRED, ErrorCode.POLICY_PRECONDITION_REQUIRED);
        if (values.size() == 1 && values.getFirst().matches("\"policy-revision-[1-9][0-9]*\"")) {
            try { return Long.parseLong(values.getFirst().substring(ETAG_PREFIX.length(), values.getFirst().length() - 1)); }
            catch (NumberFormatException invalid) { /* A revision must fit the database bigint. */ }
        }
        throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
    }

    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1)
            throw new ApiFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void rejectQuery(HttpServletRequest request) {
        if (request.getQueryString() != null)
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.UNSUPPORTED_QUERY);
    }

    private static RequestDeadline deadline(HttpServletRequest request) {
        return (RequestDeadline) request.getAttribute(RequestDeadline.ATTRIBUTE);
    }
}
