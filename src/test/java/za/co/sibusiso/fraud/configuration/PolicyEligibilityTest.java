package za.co.sibusiso.fraud.configuration;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.domain.TransactionEvent.*;

import static org.assertj.core.api.Assertions.*;
import static za.co.sibusiso.fraud.domain.TransactionEligibility.*;

class PolicyEligibilityTest {
    private final CurrencyPolicy currencies = new CurrencyPolicy(Map.of("ZAR",
            new CurrencyPolicy.Bounds(2, new PolicyAmount("999.99"))));
    private final IngestPolicy ingest = new IngestPolicy(3600, 120);
    private final Instant now = EventTime.parse("2026-10-15T12:00:00Z");

    @Test
    void currencyScaleAndAmountEligibilityAreSeparateFromStaticIdentity() {
        assertThat(currencies.check(Money.parse("ZAR", "999.99"))).isEqualTo(ELIGIBLE);
        assertThat(currencies.check(Money.parse("ZAR", "1000.00"))).isEqualTo(AMOUNT_LIMIT);
        assertThat(currencies.check(Money.parse("ZAR", "1.000"))).isEqualTo(AMOUNT_SCALE);
        assertThat(currencies.check(Money.parse("USD", "1.00"))).isEqualTo(UNSUPPORTED_CURRENCY);
    }

    @Test
    void admissionIncludesExactAgeAndSkewBoundariesAtMicrosecondPrecision() {
        assertThat(check(now.minusSeconds(3600))).isEqualTo(ELIGIBLE);
        assertThat(check(now.minusSeconds(3600).minusNanos(1000))).isEqualTo(EVENT_TOO_OLD);
        assertThat(check(now.plusSeconds(120))).isEqualTo(ELIGIBLE);
        assertThat(check(now.plusSeconds(120).plusNanos(1000))).isEqualTo(EVENT_IN_FUTURE);
    }

    private TransactionEligibility check(Instant occurredAt) {
        var event = new TransactionEvent("event-1", "customer-1", "card-1", Type.CARD_PURCHASE,
                Status.BOOKED, Channel.ECOMMERCE, Money.parse("ZAR", "1.00"), "GROCERIES", occurredAt, null);
        return ingest.checkNewEvent(event, currencies, now);
    }
}
