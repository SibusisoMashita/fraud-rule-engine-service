package za.co.sibusiso.fraud.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** Fixed v1 wire/storage bounds; business thresholds belong to the active policy. */
public final class ContractChecks {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern AMOUNT = Pattern.compile("(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,4})?");

    private ContractChecks() { }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static String identifier(String value) {
        require(value != null && IDENTIFIER.matcher(value).matches(), "Invalid v1 identifier");
        return value;
    }

    public static String text(String value, int maximum) {
        scalarText(value, maximum);
        require(!value.isBlank(), "Text cannot be blank");
        require(value.codePoints().noneMatch(Character::isISOControl), "Control characters are not allowed");
        return value;
    }

    public static String scalarText(String value, int maximum) {
        Objects.requireNonNull(value, "Text is required");
        require(value.codePointCount(0, value.length()) <= maximum, "Invalid text length");
        require(validSurrogates(value), "Invalid Unicode text");
        return value;
    }

    private static boolean validSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    public static BigDecimal amount(String value) {
        require(value != null && AMOUNT.matcher(value).matches(), "Amount must be a plain v1 decimal string");
        BigDecimal decimal = new BigDecimal(value);
        require(decimal.signum() > 0, "Amount must be positive");
        return decimal;
    }

    public static BigDecimal amount(BigDecimal value) {
        Objects.requireNonNull(value, "Amount is required");
        require(value.scale() >= 0 && value.scale() <= 4 && value.precision() - value.scale() <= 16,
                "Amount exceeds numeric(20,4) bounds");
        require(value.signum() > 0, "Amount must be positive");
        return value;
    }

    public static String currency(String value) {
        require(value != null && value.matches("[A-Z]{3}"), "Invalid currency code");
        return value;
    }

    public static String decimal(BigDecimal value) {
        Objects.requireNonNull(value, "Decimal is required");
        // Check before expanding exponent notation to avoid unbounded allocation.
        require(value.precision() <= 32 && Math.abs((long) value.scale()) <= 32, "Decimal is too large");
        String result = value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
        require(result.length() <= 32, "Decimal exceeds the v1 numeric token bound");
        return result;
    }
}
