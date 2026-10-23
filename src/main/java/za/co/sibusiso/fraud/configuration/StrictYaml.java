package za.co.sibusiso.fraud.configuration;

import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import za.co.sibusiso.fraud.domain.ContractChecks;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

public final class StrictYaml {
    // Bootstrap bounds are independent of the settings being loaded.
    private static final int MAX_BYTES = 1_048_576;
    private final JsonMapper mapper =
            JsonMapper.builder()
                    .enable(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .withCoercionConfig(
                            LogicalType.Textual,
                            coercion -> {
                                coercion.setCoercion(
                                        CoercionInputShape.Integer, CoercionAction.Fail);
                                coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                                coercion.setCoercion(
                                        CoercionInputShape.Boolean, CoercionAction.Fail);
                            })
                    .build();

    public static Resource localResource(
            org.springframework.core.io.ResourceLoader loader, String location) {
        ContractChecks.require(
                location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)
                        || location.startsWith(ResourceUtils.FILE_URL_PREFIX),
                "Configuration locations must use classpath: or file:");
        return loader.getResource(location);
    }

    public <T> T read(Resource resource, Class<T> type) {
        return read(readText(resource), type);
    }

    public String readText(Resource resource) {
        try (var stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            ContractChecks.require(
                    bytes.length <= MAX_BYTES, "Configuration exceeds bootstrap byte limit");
            String text =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return text;
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot read UTF-8 configuration: " + resource.getDescription(), failure);
        }
    }

    public <T> T read(String text, Class<T> type) {
        ContractChecks.require(
                text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES,
                "Configuration exceeds bootstrap byte limit");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(MAX_BYTES);
        var dumper = new DumperOptions();
        var yaml =
                new Yaml(
                        new SafeConstructor(options),
                        new Representer(dumper),
                        dumper,
                        options,
                        new JsonScalars());
        for (var event : yaml.parse(new StringReader(text))) {
            ContractChecks.require(
                    !(event instanceof AliasEvent)
                            && !(event instanceof NodeEvent node && node.getAnchor() != null)
                            && !(event instanceof ScalarEvent scalar && scalar.getTag() != null)
                            && !(event instanceof CollectionStartEvent collection
                                    && collection.getTag() != null),
                    "Configuration tags, anchors and aliases are not supported");
        }
        Object document = yaml.load(text);
        ContractChecks.require(
                document instanceof Map<?, ?>, "Configuration must be one mapping document");
        validateKeys(document);
        return convert(document, type);
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    private void validateKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach(
                    (key, child) -> {
                        ContractChecks.require(
                                key instanceof String && !key.equals("<<"),
                                "Configuration keys must be strings; merges are not supported");
                        validateKeys(child);
                    });
        } else if (value instanceof Iterable<?> list) list.forEach(this::validateKeys);
    }

    /** JSON scalar spellings avoid YAML 1.1's implicit yes/on, octal and timestamp conversions. */
    private static final class JsonScalars extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|false)$"), "tf");
            addImplicitResolver(Tag.NULL, Pattern.compile("^(?:null|~|)$"), "n~\0");
            addImplicitResolver(Tag.INT, Pattern.compile("^-?(?:0|[1-9][0-9]*)$"), "-0123456789");
            addImplicitResolver(
                    Tag.FLOAT,
                    Pattern.compile(
                            "^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+(?:[eE][+-]?[0-9]+)?|[eE][+-]?[0-9]+)$"),
                    "-0123456789");
        }
    }
}
