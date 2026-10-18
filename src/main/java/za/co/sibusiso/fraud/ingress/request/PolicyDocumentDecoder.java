package za.co.sibusiso.fraud.ingress.request;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;

/** Bounded JSON at the HTTP boundary; typed policy binding reuses configuration validation. */
public final class PolicyDocumentDecoder {
    private final StrictYaml yaml;
    private final JsonRequestReader json;

    public PolicyDocumentDecoder(ServiceLimits limits, StrictYaml yaml) {
        this.yaml = yaml;
        json = new JsonRequestReader(limits.api());
    }

    public PolicyLoader.Document decode(InputStream input, long length, String contentType, String encoding)
            throws IOException {
        var document = json.read(input, length, contentType, encoding);
        if (document == null || !document.isObject())
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_JSON);
        try {
            return yaml.convert(document, PolicyLoader.Document.class);
        } catch (IllegalArgumentException | JacksonException failure) {
            throw new ApiFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.POLICY_INVALID, 0, failure);
        }
    }
}
