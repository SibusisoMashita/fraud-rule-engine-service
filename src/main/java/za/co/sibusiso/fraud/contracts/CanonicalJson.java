package za.co.sibusiso.fraud.contracts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.domain.ContractChecks;

/** Service canonicalization v1, deliberately not a claim of RFC 8785 compliance. */
public final class CanonicalJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CanonicalJson() { }

    public static String encode(Object value) {
        var bytes = new ByteArrayOutputStream();
        try (JsonGenerator output = MAPPER.tokenStreamFactory().createGenerator(bytes)) {
            write(MAPPER.valueToTree(value), output);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    public static String sha256(String namespace, Object value) {
        try {
            byte[] bytes = (namespace + "\n" + encode(value)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java requires SHA-256 support", impossible);
        }
    }

    private static void write(JsonNode node, JsonGenerator output) {
        if (node.isObject()) {
            output.writeStartObject();
            node.properties().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                output.writeName(entry.getKey());
                write(entry.getValue(), output);
            });
            output.writeEndObject();
        } else if (node.isArray()) {
            output.writeStartArray();
            node.forEach(child -> write(child, output));
            output.writeEndArray();
        } else if (node.isNumber()) output.writeNumber(ContractChecks.decimal(node.asDecimal()));
        else if (node.isString()) output.writeString(node.asString());
        else if (node.isBoolean()) output.writeBoolean(node.asBoolean());
        else if (node.isNull()) output.writeNull();
        else throw new IllegalArgumentException("Unsupported canonical value");
    }
}
