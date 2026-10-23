package za.co.sibusiso.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Field;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Reason;

public record TransactionEvent(String eventId, String customerId, String instrumentId,
        Type transactionType, Status status, Channel channel, Money money,
        String category, Instant occurredAt, PhysicalLocation physicalLocation) {
    public enum Type { CARD_PURCHASE, ATM_WITHDRAWAL }
    public enum Status { BOOKED }
    public enum Channel { CARD_PRESENT, ECOMMERCE, ATM }
    public enum LocationSource { VERIFIED_ATM_TERMINAL, VERIFIED_CARD_PRESENT_TERMINAL }

    public TransactionEvent {
        eventId = identifier(Field.EVENT_ID, eventId);
        customerId = identifier(Field.CUSTOMER_ID, customerId);
        instrumentId = identifier(Field.INSTRUMENT_ID, instrumentId);
        Objects.requireNonNull(transactionType, "Transaction type is required");
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(channel, "Channel is required");
        Objects.requireNonNull(money, "Money is required");
        category = text(Field.CATEGORY, category, 64);
        occurredAt = EventTime.validate(occurredAt);
        TransactionValidationException.require((transactionType == Type.ATM_WITHDRAWAL) == (channel == Channel.ATM),
                Field.CHANNEL, Reason.TYPE_CHANNEL_MISMATCH,
                "Transaction type and channel disagree");
        if (physicalLocation != null) {
            TransactionValidationException.require(channel != Channel.ECOMMERCE,
                    Field.PHYSICAL_LOCATION, Reason.ONLINE_PHYSICAL_LOCATION,
                    "Online payments cannot assert physical presence");
            TransactionValidationException.require((channel == Channel.ATM)
                    == (physicalLocation.source() == LocationSource.VERIFIED_ATM_TERMINAL),
                    Field.LOCATION_SOURCE, Reason.LOCATION_SOURCE_CHANNEL_MISMATCH,
                    "Location source and channel disagree");
        }
    }

    public record PhysicalLocation(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracyKm,
            LocationSource source, String city) {
        public PhysicalLocation {
            decimal(Field.LATITUDE, Reason.INVALID_COORDINATE, latitude);
            decimal(Field.LONGITUDE, Reason.INVALID_COORDINATE, longitude);
            decimal(Field.ACCURACY_KM, Reason.INVALID_ACCURACY, accuracyKm);
            TransactionValidationException.require(latitude.abs().compareTo(BigDecimal.valueOf(90)) <= 0,
                    Field.LATITUDE, Reason.INVALID_COORDINATE, "Latitude out of range");
            TransactionValidationException.require(longitude.abs().compareTo(BigDecimal.valueOf(180)) <= 0,
                    Field.LONGITUDE, Reason.INVALID_COORDINATE, "Longitude out of range");
            TransactionValidationException.require(accuracyKm.signum() >= 0,
                    Field.ACCURACY_KM, Reason.INVALID_ACCURACY, "Accuracy radius cannot be negative");
            Objects.requireNonNull(source, "Location source is required");
            if (city != null && !city.isEmpty()) city = text(Field.CITY, city, 128);
        }
    }

    private static String identifier(Field field, String value) {
        return TransactionValidationException.validate(field, Reason.INVALID_IDENTIFIER,
                () -> ContractChecks.identifier(value));
    }

    private static String text(Field field, String value, int maximum) {
        return TransactionValidationException.validate(field, Reason.INVALID_TEXT,
                () -> ContractChecks.text(value, maximum));
    }

    private static void decimal(Field field, Reason reason, BigDecimal value) {
        TransactionValidationException.validate(field, reason, () -> ContractChecks.decimal(value));
    }
}
