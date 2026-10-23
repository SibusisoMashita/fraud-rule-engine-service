package za.co.sibusiso.fraud.domain;

import java.time.DateTimeException;
import java.util.Objects;
import java.util.function.Supplier;

/** Identifies a failed transaction invariant without using its exception text as an API message. */
public final class TransactionValidationException extends IllegalArgumentException {
    public enum Field {
        EVENT_ID, CUSTOMER_ID, INSTRUMENT_ID, TRANSACTION_TYPE, STATUS, CHANNEL,
        AMOUNT, CURRENCY, CATEGORY, OCCURRED_AT, PHYSICAL_LOCATION,
        LATITUDE, LONGITUDE, ACCURACY_KM, LOCATION_SOURCE, CITY
    }

    public enum Reason {
        INVALID_IDENTIFIER, INVALID_VALUE, INVALID_AMOUNT, INVALID_CURRENCY, INVALID_TEXT,
        INVALID_TIMESTAMP, INVALID_COORDINATE, INVALID_ACCURACY, TYPE_CHANNEL_MISMATCH,
        ONLINE_PHYSICAL_LOCATION, LOCATION_SOURCE_CHANNEL_MISMATCH,
        UNSUPPORTED_CURRENCY, AMOUNT_SCALE, AMOUNT_LIMIT, EVENT_TOO_OLD, EVENT_IN_FUTURE
    }

    private final Field field;
    private final Reason reason;

    private TransactionValidationException(Field field, Reason reason, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.field = Objects.requireNonNull(field);
        this.reason = Objects.requireNonNull(reason);
    }

    public Field field() { return field; }
    public Reason reason() { return reason; }

    public static <T> T validate(Field field, Reason reason, Supplier<T> validation) {
        try {
            return validation.get();
        } catch (TransactionValidationException failure) {
            throw failure;
        } catch (IllegalArgumentException | NullPointerException | DateTimeException failure) {
            throw new TransactionValidationException(field, reason, failure);
        }
    }

    static void require(boolean condition, Field field, Reason reason, String message) {
        if (!condition) {
            throw new TransactionValidationException(field, reason, new IllegalArgumentException(message));
        }
    }
}
