package za.co.sibusiso.fraud.ingress.request;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;

import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.domain.TransactionValidationException;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

class TransactionDecoderTest {
    static final String VALID =
            """
            {"eventId":"evt-1","customerId":"customer-1","instrumentId":"instrument-1",
             "transactionType":"CARD_PURCHASE","status":"BOOKED","channel":"ECOMMERCE",
             "amount":"1500.00","currency":"ZAR","category":"GROCERIES","occurredAt":"2026-10-15T10:00:00+02:00"}
            """;
    private final TransactionDecoder decoder =
            new TransactionDecoder(
                    new StrictYaml()
                            .read(
                                    new ClassPathResource("config/service.example.yaml"),
                                    ServiceLimits.class));

    @Test
    void decodesExactDecimalsAndOffsetTimeWithoutCurrentBusinessPolicy() throws Exception {
        var event = decode(VALID);
        assertThat(event.money().amount().toPlainString()).isEqualTo("1500.00");
        assertThat(event.occurredAt().toString()).isEqualTo("2026-10-15T08:00:00Z");
        assertThat(decode(VALID.replace("ZAR", "USD")).money().currency()).isEqualTo("USD");
    }

    @Test
    void preservesValidLeapDayOffsetAndMicroseconds() throws Exception {
        var event =
                decode(
                        VALID.replace(
                                "2026-10-15T10:00:00+02:00", "2024-02-29T10:00:00.123456+02:00"));
        assertThat(event.occurredAt().toString()).isEqualTo("2024-02-29T08:00:00.123456Z");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "2026-02-30T10:00:00Z", "2026-10-15T10:00:00+19:00",
                "2026-10-15T10:00:00+18:01", "2026-10-15T10:00:60Z",
                "0001-01-01T00:00:00+18:00", "9999-12-31T23:59:59-18:00"
            })
    void invalidTimeRetainsAnInternalCauseAndFixedPublicMessage(String timestamp) {
        assertThatThrownBy(() -> decode(VALID.replace("2026-10-15T10:00:00+02:00", timestamp)))
                .isInstanceOfSatisfying(
                        ApiFailure.class,
                        failure -> {
                            assertThat(failure.status().value()).isEqualTo(422);
                            assertThat(failure.code()).isEqualTo("INVALID_TRANSACTION");
                            assertThat(failure.getCause())
                                    .isInstanceOf(TransactionValidationException.class)
                                    .hasCauseInstanceOf(RuntimeException.class);
                            assertThat(failure.validationError().problem())
                                    .containsEntry("field", "occurredAt")
                                    .containsEntry("code", "INVALID_TIMESTAMP")
                                    .containsEntry(
                                            "message",
                                            "Use a real date and time with a UTC offset and at most six fractional-second digits.");
                        });
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsMalformedOrUnsafeInput(String input, int status) {
        assertThatThrownBy(() -> decode(input))
                .isInstanceOfSatisfying(
                        ApiFailure.class,
                        failure -> assertThat(failure.status().value()).isEqualTo(status));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("", 400),
                Arguments.of("[]", 400),
                Arguments.of("null", 400),
                Arguments.of(VALID + "{}", 400),
                Arguments.of(
                        VALID.replace("\"eventId\":", "\"scope\":\"forged\",\"eventId\":"), 400),
                Arguments.of(
                        VALID.replace("\"eventId\":", "\"eventId\":\"duplicate\",\"eventId\":"),
                        400),
                Arguments.of(VALID.replace("\"1500.00\"", "1500.00"), 400),
                Arguments.of(VALID.replace("\"customer-1\"", "null"), 400),
                Arguments.of(VALID.replace("BOOKED", "PENDING"), 422),
                Arguments.of(VALID.replace("1500.00", "0.00"), 422),
                Arguments.of(VALID.replace("1500.00", "1e3"), 422),
                Arguments.of(VALID.replace("10:00:00+02:00", "10:00:00.1234567Z"), 422),
                Arguments.of(VALID.replace("GROCERIES", "x".repeat(65)), 400),
                Arguments.of(VALID.replace("evt-1", "x".repeat(129)), 400),
                Arguments.of(VALID.replace("\"category\":\"GROCERIES\",", ""), 400),
                Arguments.of(
                        VALID.replace("\"amount\":", "\"physicalLocation\":null,\"amount\":"), 400),
                Arguments.of("{\"deep\":" + "[".repeat(10) + "0" + "]".repeat(10) + "}", 400),
                Arguments.of("{\"n\":" + "1".repeat(33) + "}", 400),
                Arguments.of(" ".repeat(16385), 413));
    }

    @Test
    void boundsUnknownLengthBodiesAndRejectsEncodingConfusion() {
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        new ByteArrayInputStream(new byte[] {(byte) 0xff}),
                                        -1,
                                        "application/json",
                                        null))
                .isInstanceOfSatisfying(
                        ApiFailure.class, e -> assertThat(e.status().value()).isEqualTo(400));
        for (String type : new String[] {"text/plain", "application/json;charset=UTF-16", ""}) {
            assertThatThrownBy(
                            () ->
                                    decoder.decode(
                                            new ByteArrayInputStream(
                                                    VALID.getBytes(StandardCharsets.UTF_8)),
                                            -1,
                                            type,
                                            null))
                    .isInstanceOfSatisfying(
                            ApiFailure.class, e -> assertThat(e.status().value()).isEqualTo(415));
        }
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        new ByteArrayInputStream(new byte[0]),
                                        16385,
                                        "application/json",
                                        null))
                .isInstanceOfSatisfying(
                        ApiFailure.class, e -> assertThat(e.status().value()).isEqualTo(413));
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        new ByteArrayInputStream(new byte[0]),
                                        0,
                                        "application/json",
                                        "gzip"))
                .isInstanceOfSatisfying(
                        ApiFailure.class, e -> assertThat(e.status().value()).isEqualTo(415));
    }

    private za.co.sibusiso.fraud.domain.TransactionEvent decode(String input) throws Exception {
        return decoder.decode(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                -1,
                "application/json",
                null);
    }
}
