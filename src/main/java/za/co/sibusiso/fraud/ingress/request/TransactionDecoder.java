package za.co.sibusiso.fraud.ingress.request;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;

import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.EventTime;
import za.co.sibusiso.fraud.domain.Money;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.domain.TransactionEvent.Channel;
import za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource;
import za.co.sibusiso.fraud.domain.TransactionEvent.PhysicalLocation;
import za.co.sibusiso.fraud.domain.TransactionEvent.Status;
import za.co.sibusiso.fraud.domain.TransactionEvent.Type;
import za.co.sibusiso.fraud.domain.TransactionValidationException;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Field;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Reason;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ValidationError;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Explicit HTTP-to-domain adapter: no scalar coercion, ignored fields or policy-dependent identity.
 */
public final class TransactionDecoder {
    private static final Set<String> REQUIRED =
            Set.of(
                    "eventId",
                    "customerId",
                    "instrumentId",
                    "transactionType",
                    "status",
                    "channel",
                    "amount",
                    "currency",
                    "category",
                    "occurredAt");
    private final ServiceLimits.Api limits;
    private final JsonRequestReader json;

    public TransactionDecoder(ServiceLimits service) {
        this.limits = service.api();
        json = new JsonRequestReader(limits);
    }

    public TransactionEvent decode(
            InputStream input, long contentLength, String contentType, String encoding)
            throws IOException {
        JsonNode root = json.read(input, contentLength, contentType, encoding);
        fields(root, REQUIRED, Set.of("physicalLocation"));
        try {
            PhysicalLocation location = null;
            if (root.has("physicalLocation")) {
                var value = root.get("physicalLocation");
                fields(
                        value,
                        Set.of("latitude", "longitude", "accuracyKm", "source"),
                        Set.of("city"));
                String city = value.has("city") ? text(value, "city") : null;
                if (city != null
                        && city.codePointCount(0, city.length()) > limits.maxCityLength()) {
                    throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
                }
                location =
                        new PhysicalLocation(
                                number(value, "latitude"),
                                number(value, "longitude"),
                                number(value, "accuracyKm"),
                                enumeration(
                                        LocationSource.class,
                                        text(value, "source"),
                                        Field.LOCATION_SOURCE),
                                city);
            }
            String category = text(root, "category");
            if (category.codePointCount(0, category.length()) > limits.maxCategoryLength()) {
                throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
            }
            return new TransactionEvent(
                    id(root, "eventId"),
                    id(root, "customerId"),
                    id(root, "instrumentId"),
                    enumeration(Type.class, text(root, "transactionType"), Field.TRANSACTION_TYPE),
                    enumeration(Status.class, text(root, "status"), Field.STATUS),
                    enumeration(Channel.class, text(root, "channel"), Field.CHANNEL),
                    Money.parse(text(root, "currency"), text(root, "amount")),
                    category,
                    EventTime.parse(text(root, "occurredAt")),
                    location);
        } catch (TransactionValidationException invalidDomain) {
            throw new ApiFailure(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            ErrorCode.INVALID_TRANSACTION,
                            0,
                            invalidDomain)
                    .withValidationError(ValidationError.from(invalidDomain));
        }
    }

    private static <E extends Enum<E>> E enumeration(Class<E> type, String value, Field field) {
        return TransactionValidationException.validate(
                field, Reason.INVALID_VALUE, () -> Enum.valueOf(type, value));
    }

    private String id(JsonNode root, String name) {
        String value = text(root, name);
        if (value.length() > limits.maxIdentifierLength()) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
        }
        return value;
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
        }
        return value.asString();
    }

    private static BigDecimal number(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isNumber()) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
        }
        return value.asDecimal();
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_JSON);
        }
        var actual = new HashSet<String>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        var allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FIELD);
        }
    }
}
