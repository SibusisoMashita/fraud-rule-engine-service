package za.co.sibusiso.fraud.contracts;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.domain.TransactionEvent.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

class CanonicalTransactionTest {
    @Test
    void matchesAnIndependentRequestVector() {
        var event =
                event(
                        "event-1",
                        "customer-1",
                        "card-1",
                        Channel.ECOMMERCE,
                        Money.parse("ZAR", "100.00"),
                        "GROCERIES",
                        EventTime.parse("2026-10-15T12:00:00.123456+02:00"),
                        null);
        String expected =
                """
                {"amount":"100","canonicalizationVersion":1,"category":"GROCERIES","channel":"ECOMMERCE","currency":"ZAR","customerId":"customer-1","eventId":"event-1","instrumentId":"card-1","occurredAt":"2026-10-15T10:00:00.123456Z","physicalLocation":null,"scope":"demo","status":"BOOKED","transactionType":"CARD_PURCHASE"}
                """
                        .strip();
        assertThat(CanonicalJson.encode(CanonicalTransaction.document("demo", event)))
                .isEqualTo(expected);
        assertThat(CanonicalTransaction.hash("demo", event))
                .isEqualTo("452be04fbf6a34ca7a5b4a5a8d7d22299aa13f0ead34ee4ebbe39de0e08ace23");
    }

    @Test
    void equivalentMoneyOffsetsAndCoordinatesPreserveIdentity() {
        var first =
                physical(
                        "100.00",
                        "-26.204100",
                        "28.047300",
                        "0.10",
                        "2026-10-15T12:00:00+02:00",
                        "Johannesburg");
        var second =
                physical(
                        "100",
                        "-26.2041",
                        "28.0473",
                        "0.1",
                        "2026-10-15T10:00:00Z",
                        "Johannesburg");
        assertThat(CanonicalTransaction.hash("demo", first))
                .isEqualTo(CanonicalTransaction.hash("demo", second));
    }

    @Test
    void everyInputFactAndScopeParticipatesInIdentity() {
        Instant time = EventTime.parse("2026-10-15T10:00:00Z");
        Money money = Money.parse("ZAR", "100.00");
        var base =
                event(
                        "event-1",
                        "customer-1",
                        "card-1",
                        Channel.ECOMMERCE,
                        money,
                        "GROCERIES",
                        time,
                        null);
        List<TransactionEvent> mutations =
                List.of(
                        event(
                                "event-2",
                                "customer-1",
                                "card-1",
                                Channel.ECOMMERCE,
                                money,
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-2",
                                "card-1",
                                Channel.ECOMMERCE,
                                money,
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-2",
                                Channel.ECOMMERCE,
                                money,
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-1",
                                Channel.CARD_PRESENT,
                                money,
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-1",
                                Channel.ATM,
                                money,
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-1",
                                Channel.ECOMMERCE,
                                Money.parse("USD", "100"),
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-1",
                                Channel.ECOMMERCE,
                                Money.parse("ZAR", "100.01"),
                                "GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-1",
                                Channel.ECOMMERCE,
                                money,
                                " GROCERIES",
                                time,
                                null),
                        event(
                                "event-1",
                                "customer-1",
                                "card-1",
                                Channel.ECOMMERCE,
                                money,
                                "GROCERIES",
                                time.plusNanos(1000),
                                null));
        String hash = CanonicalTransaction.hash("demo", base);
        mutations.forEach(
                changed ->
                        assertThat(CanonicalTransaction.hash("demo", changed)).isNotEqualTo(hash));
        assertThat(CanonicalTransaction.hash("other-scope", base)).isNotEqualTo(hash);
        var physical = physical("100", "-26", "28", "1", time.toString(), "Johannesburg");
        String physicalHash = CanonicalTransaction.hash("demo", physical);
        assertThat(
                        CanonicalTransaction.hash(
                                "demo",
                                physical("100", "-27", "28", "1", time.toString(), "Johannesburg")))
                .isNotEqualTo(physicalHash);
        assertThat(
                        CanonicalTransaction.hash(
                                "demo",
                                physical("100", "-26", "29", "1", time.toString(), "Johannesburg")))
                .isNotEqualTo(physicalHash);
        assertThat(
                        CanonicalTransaction.hash(
                                "demo",
                                physical("100", "-26", "28", "2", time.toString(), "Johannesburg")))
                .isNotEqualTo(physicalHash);
        assertThat(
                        CanonicalTransaction.hash(
                                "demo",
                                physical("100", "-26", "28", "1", time.toString(), "Durban")))
                .isNotEqualTo(physicalHash);
    }

    private static TransactionEvent physical(
            String amount, String lat, String lon, String accuracy, String at, String city) {
        return event(
                "event-1",
                "customer-1",
                "card-1",
                Channel.ATM,
                Money.parse("ZAR", amount),
                "GROCERIES",
                EventTime.parse(at),
                new PhysicalLocation(
                        new BigDecimal(lat),
                        new BigDecimal(lon),
                        new BigDecimal(accuracy),
                        LocationSource.VERIFIED_ATM_TERMINAL,
                        city));
    }

    private static TransactionEvent event(
            String id,
            String customer,
            String instrument,
            Channel channel,
            Money money,
            String category,
            Instant at,
            PhysicalLocation location) {
        return new TransactionEvent(
                id,
                customer,
                instrument,
                channel == Channel.ATM ? Type.ATM_WITHDRAWAL : Type.CARD_PURCHASE,
                Status.BOOKED,
                channel,
                money,
                category,
                at,
                location);
    }
}
