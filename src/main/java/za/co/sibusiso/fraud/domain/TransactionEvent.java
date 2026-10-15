package za.co.sibusiso.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record TransactionEvent(String eventId, String customerId, String instrumentId,
        Type transactionType, Status status, Channel channel, Money money,
        String category, Instant occurredAt, PhysicalLocation physicalLocation) {
    public enum Type { CARD_PURCHASE, ATM_WITHDRAWAL }
    public enum Status { BOOKED }
    public enum Channel { CARD_PRESENT, ECOMMERCE, ATM }
    public enum LocationSource { VERIFIED_ATM_TERMINAL, VERIFIED_CARD_PRESENT_TERMINAL }

    public TransactionEvent {
        eventId = ContractChecks.identifier(eventId);
        customerId = ContractChecks.identifier(customerId);
        instrumentId = ContractChecks.identifier(instrumentId);
        Objects.requireNonNull(transactionType, "Transaction type is required");
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(channel, "Channel is required");
        Objects.requireNonNull(money, "Money is required");
        category = ContractChecks.text(category, 64);
        occurredAt = EventTime.validate(occurredAt);
        ContractChecks.require((transactionType == Type.ATM_WITHDRAWAL) == (channel == Channel.ATM),
                "Transaction type and channel disagree");
        if (physicalLocation != null) {
            ContractChecks.require(channel != Channel.ECOMMERCE, "Online payments cannot assert physical presence");
            ContractChecks.require((channel == Channel.ATM)
                    == (physicalLocation.source() == LocationSource.VERIFIED_ATM_TERMINAL),
                    "Location source and channel disagree");
        }
    }

    public record PhysicalLocation(BigDecimal latitude, BigDecimal longitude, BigDecimal accuracyKm,
            LocationSource source, String city) {
        public PhysicalLocation {
            ContractChecks.decimal(latitude);
            ContractChecks.decimal(longitude);
            ContractChecks.decimal(accuracyKm);
            ContractChecks.require(latitude.abs().compareTo(BigDecimal.valueOf(90)) <= 0, "Latitude out of range");
            ContractChecks.require(longitude.abs().compareTo(BigDecimal.valueOf(180)) <= 0, "Longitude out of range");
            ContractChecks.require(accuracyKm.signum() >= 0, "Accuracy radius cannot be negative");
            Objects.requireNonNull(source, "Location source is required");
            if (city != null && !city.isEmpty()) city = ContractChecks.text(city, 128);
        }
    }
}
