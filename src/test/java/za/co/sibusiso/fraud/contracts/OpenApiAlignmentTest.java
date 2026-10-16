package za.co.sibusiso.fraud.contracts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

import static org.assertj.core.api.Assertions.*;

class OpenApiAlignmentTest {
    @Test
    void domainStatusVocabularyAndExtensibleIdsMatchTheDraftContract() throws Exception {
        Map<?, ?> document = new StrictYaml().read(Files.readString(Path.of("api/openapi.yaml")), Map.class);
        Map<?, ?> schemas = object(object(document, "components"), "schemas");
        assertEnum(object(schemas, "TransactionInput"), "transactionType", TransactionEvent.Type.values());
        assertEnum(object(schemas, "TransactionInput"), "status", TransactionEvent.Status.values());
        assertEnum(object(schemas, "TransactionInput"), "channel", TransactionEvent.Channel.values());
        assertEnum(object(schemas, "PhysicalLocation"), "source", TransactionEvent.LocationSource.values());
        assertEnum(object(schemas, "RuleResult"), "status", RuleResult.Status.values());
        assertEnum(object(schemas, "Evaluation"), "decision", EvaluationOutcome.Decision.values());
        assertEnum(object(schemas, "Evaluation"), "coverage", EvaluationOutcome.Coverage.values());
        assertEnum(object(schemas, "Problem"), "outcome", ApiFailure.Outcome.values());
        assertThat(object(object(object(schemas, "RuleResult"), "properties"), "ruleId").containsKey("enum")).isFalse();
        assertThat(object(schemas, "Identifier").get("maxLength")).isEqualTo(128);
        assertThat(object(object(object(schemas, "RuleResult"), "properties"), "evidence").get("maxProperties")).isEqualTo(16);
        assertThat(object(object(object(schemas, "Evaluation"), "properties"), "ruleResults").get("maxItems")).isEqualTo(64);
    }

    private static Map<?, ?> object(Map<?, ?> parent, String key) { return (Map<?, ?>) parent.get(key); }

    private static void assertEnum(Map<?, ?> schema, String field, Enum<?>[] values) {
        List<String> published = ((List<?>) object(object(schema, "properties"), field).get("enum"))
                .stream().map(Object::toString).toList();
        assertThat(published).containsExactlyInAnyOrderElementsOf(Arrays.stream(values).map(Enum::name).toList());
    }
}
