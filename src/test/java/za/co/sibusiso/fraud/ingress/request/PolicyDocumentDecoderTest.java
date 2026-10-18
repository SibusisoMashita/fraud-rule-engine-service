package za.co.sibusiso.fraud.ingress.request;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;

import static org.assertj.core.api.Assertions.*;

class PolicyDocumentDecoderTest {
    private final StrictYaml yaml = new StrictYaml();
    private final ServiceLimits limits = yaml.read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class);
    private final PolicyDocumentDecoder decoder = new PolicyDocumentDecoder(limits, yaml);
    private final String policy = JsonMapper.builder().build().writeValueAsString(
            yaml.read(new ClassPathResource("config/policy.example.yaml"), PolicyLoader.Document.class));

    @Test void acceptsTheCompletePolicyDocumentWithoutChangingMonetaryStrings() throws Exception {
        var document = read(policy, "application/json", null);
        assertThat(document.supportedCurrencies().get("ZAR").maxAmount().decimal()).isEqualTo("999999999999.99");
        assertThat(document.rules()).hasSize(5);
        var high = (java.util.Map<?, ?>) document.rules().get("highAmount");
        assertThat(((java.util.Map<?, ?>) high.get("thresholds")).get("ZAR")).isEqualTo("25000.00");
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{} {}", "{\"schemaVersion\":1,\"schemaVersion\":1}", "{", ""})
    void rejectsMalformedOrAmbiguousJson(String text) {
        assertThatThrownBy(() -> read(text, "application/json", null)).isInstanceOf(ApiFailure.class);
    }

    @Test void rejectsUnknownAndMissingDocumentFieldsInsteadOfSilentlyDroppingThem() {
        for (String text : new String[] {policy.replaceFirst("\\{", "{\"typo\":true,"),
                policy.replace("\"schemaVersion\":1,", "")})
            assertThatThrownBy(() -> read(text, "application/json", null)).isInstanceOfSatisfying(ApiFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo("POLICY_INVALID"));
    }

    @Test void enforcesTheExistingHttpMediaEncodingAndStreamingSizeLimits() {
        for (String type : new String[] {"text/yaml", "application/json;charset=UTF-16"})
            assertThatThrownBy(() -> read(policy, type, null)).isInstanceOfSatisfying(ApiFailure.class,
                    failure -> assertThat(failure.status().value()).isEqualTo(415));
        assertThatThrownBy(() -> read(policy, "application/json", "gzip")).isInstanceOf(ApiFailure.class);
        String oversized = " ".repeat(limits.api().maxBodyBytes()) + policy;
        assertThatThrownBy(() -> decoder.decode(new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8)),
                -1, "application/json", null)).isInstanceOfSatisfying(ApiFailure.class,
                failure -> assertThat(failure.code()).isEqualTo("BODY_TOO_LARGE"));
    }

    private PolicyLoader.Document read(String text, String type, String encoding) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return decoder.decode(new ByteArrayInputStream(bytes), bytes.length, type, encoding);
    }
}
