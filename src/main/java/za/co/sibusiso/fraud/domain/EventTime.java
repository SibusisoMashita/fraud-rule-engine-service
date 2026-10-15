package za.co.sibusiso.fraud.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EventTime {
    private static final Pattern WIRE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})");

    private EventTime() { }

    public static Instant parse(String value) {
        ContractChecks.require(value != null && WIRE.matcher(value).matches(),
                "Timestamp requires an offset and at most microsecond precision");
        return validate(OffsetDateTime.parse(value).toInstant());
    }

    public static Instant validate(Instant value) {
        Objects.requireNonNull(value, "Timestamp is required");
        ContractChecks.require(value.getNano() % 1000 == 0, "Sub-microsecond timestamp is not supported");
        int year = value.atOffset(ZoneOffset.UTC).getYear();
        ContractChecks.require(year >= 1 && year <= 9999, "Timestamp is outside the v1 UTC year range");
        return value;
    }
}
