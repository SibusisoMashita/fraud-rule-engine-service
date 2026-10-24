package za.co.sibusiso.fraud.ingress.error;

import za.co.sibusiso.fraud.domain.TransactionEligibility;
import za.co.sibusiso.fraud.domain.TransactionValidationException;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Field;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Reason;

import java.util.Map;
import java.util.Objects;

/** Only known transaction fields and server-authored messages may reach the public response. */
public record ValidationError(Field field, Reason reason) {
    public ValidationError {
        Objects.requireNonNull(field);
        Objects.requireNonNull(reason);
    }

    public static ValidationError from(TransactionValidationException failure) {
        return new ValidationError(failure.field(), failure.reason());
    }

    public static ValidationError forEligibility(String code) {
        final TransactionEligibility eligibility;
        try {
            eligibility = TransactionEligibility.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException notEligibility) {
            return null;
        }
        return switch (eligibility) {
            case ELIGIBLE -> null;
            case UNSUPPORTED_CURRENCY ->
                    new ValidationError(Field.CURRENCY, Reason.UNSUPPORTED_CURRENCY);
            case AMOUNT_SCALE -> new ValidationError(Field.AMOUNT, Reason.AMOUNT_SCALE);
            case AMOUNT_LIMIT -> new ValidationError(Field.AMOUNT, Reason.AMOUNT_LIMIT);
            case EVENT_TOO_OLD -> new ValidationError(Field.OCCURRED_AT, Reason.EVENT_TOO_OLD);
            case EVENT_IN_FUTURE -> new ValidationError(Field.OCCURRED_AT, Reason.EVENT_IN_FUTURE);
        };
    }

    public Map<String, String> problem() {
        return Map.of("field", path(), "code", reason.name(), "message", message());
    }

    private String path() {
        return switch (field) {
            case EVENT_ID -> "eventId";
            case CUSTOMER_ID -> "customerId";
            case INSTRUMENT_ID -> "instrumentId";
            case TRANSACTION_TYPE -> "transactionType";
            case STATUS -> "status";
            case CHANNEL -> "channel";
            case AMOUNT -> "amount";
            case CURRENCY -> "currency";
            case CATEGORY -> "category";
            case OCCURRED_AT -> "occurredAt";
            case PHYSICAL_LOCATION -> "physicalLocation";
            case LATITUDE -> "physicalLocation.latitude";
            case LONGITUDE -> "physicalLocation.longitude";
            case ACCURACY_KM -> "physicalLocation.accuracyKm";
            case LOCATION_SOURCE -> "physicalLocation.source";
            case CITY -> "physicalLocation.city";
        };
    }

    private String message() {
        return switch (reason) {
            case INVALID_IDENTIFIER ->
                    "Use letters, digits, dots, underscores, colons or hyphens, starting with a letter or digit.";
            case INVALID_VALUE -> "Choose a supported value for this field.";
            case INVALID_AMOUNT ->
                    "Use a positive plain decimal amount within the published precision limits.";
            case INVALID_CURRENCY -> "Use a three-letter uppercase currency code.";
            case INVALID_TEXT ->
                    "Use non-blank text without control characters, within the published length limit.";
            case INVALID_TIMESTAMP ->
                    "Use a real date and time with a UTC offset and at most six fractional-second digits.";
            case INVALID_COORDINATE ->
                    "Use a coordinate within the published latitude or longitude range and precision limits.";
            case INVALID_ACCURACY ->
                    "Use a non-negative accuracy radius within the published precision limits.";
            case TYPE_CHANNEL_MISMATCH ->
                    "Use ATM for ATM withdrawals, or a card channel for card purchases.";
            case ONLINE_PHYSICAL_LOCATION -> "Omit physical location for an online transaction.";
            case LOCATION_SOURCE_CHANNEL_MISMATCH ->
                    "Use a location source that matches the transaction channel.";
            case UNSUPPORTED_CURRENCY -> "Use a currency enabled by the active policy.";
            case AMOUNT_SCALE -> "Use the decimal scale allowed by the active currency policy.";
            case AMOUNT_LIMIT -> "Use an amount within the active currency policy limit.";
            case EVENT_TOO_OLD -> "Use a time within the active policy's event-age window.";
            case EVENT_IN_FUTURE -> "Use a time within the active policy's future-skew window.";
        };
    }
}
