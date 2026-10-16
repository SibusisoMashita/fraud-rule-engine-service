package za.co.sibusiso.fraud.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.CanonicalJson;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EventTime;

/** Signed navigation state, never an authorization credential. Shared by all replicas. */
@Component
@Profile("!migrate & !policy-admin")
public final class EvaluationCursor {
    public record State(
            int version, String binding, String from, String to, String after, String id) {}

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int CURSOR_VERSION = 1;
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final String NAMESPACE = "fraud-evaluation-cursor-v1\n";
    private final byte[] key;
    private final int maximumLength;
    private final JsonMapper json =
            JsonMapper.builder()
                    .enable(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();

    public EvaluationCursor(
            @Value("${fraud.cursor-signing-key}") String secret, ServiceLimits limits) {
        ContractChecks.require(
                secret.matches("[a-fA-F0-9]{64}"),
                "Cursor signing key must contain 32 random bytes as hexadecimal");
        key = HexFormat.of().parseHex(secret);
        maximumLength = limits.api().maxCursorLength();
        var largest =
                new State(
                        CURSOR_VERSION,
                        "f".repeat(64),
                        "9999-12-31T23:59:59.999999Z",
                        "9999-12-31T23:59:59.999999Z",
                        "9999-12-31T23:59:59.999999Z",
                        "ffffffff-ffff-ffff-ffff-ffffffffffff");
        ContractChecks.require(
                encode(largest).length() <= maximumLength,
                "Configured cursor length cannot represent the v1 cursor");
    }

    public String binding(String scope, EvaluationQuery.Request request, int pageSize) {
        return CanonicalJson.sha256(
                "fraud-evaluation-query-v1",
                Map.of(
                        "scope",
                        scope,
                        "customerId",
                        request.customerId() == null ? "" : request.customerId(),
                        "decision",
                        request.decision() == null ? "" : request.decision().name(),
                        "acceptedFrom",
                        request.from() == null ? "" : request.from().toString(),
                        "acceptedTo",
                        request.to() == null ? "" : request.to().toString(),
                        "pageSize",
                        pageSize));
    }

    public String next(
            String binding, EvaluationQuery.Window window, EvaluationQuery.Position position) {
        return encode(
                new State(
                        CURSOR_VERSION,
                        binding,
                        window.from().toString(),
                        window.to().toString(),
                        position.acceptedAt().toString(),
                        position.id().toString()));
    }

    private String encode(State state) {
        byte[] payload = CanonicalJson.encode(state).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    public State decode(String token, String expectedBinding) {
        try {
            if (token.length() > maximumLength
                    || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw invalid();
            String[] parts = token.split("\\.");
            byte[] payload = DECODER.decode(parts[0]), signature = DECODER.decode(parts[1]);
            if (!ENCODER.encodeToString(payload).equals(parts[0])
                    || !ENCODER.encodeToString(signature).equals(parts[1])
                    || !MessageDigest.isEqual(sign(payload), signature)) throw invalid();
            // Verify the MAC before parsing; unsigned input never reaches the JSON decoder.
            State state = json.readValue(payload, State.class);
            if (state.version() != CURSOR_VERSION || !expectedBinding.equals(state.binding()))
                throw invalid();
            var from = EventTime.parse(state.from());
            var to = EventTime.parse(state.to());
            var after = EventTime.parse(state.after());
            UUID id = UUID.fromString(state.id());
            if (!id.toString().equals(state.id()) || after.isBefore(from) || !after.isBefore(to))
                throw invalid();
            return state;
        } catch (FlowFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            var mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            mac.update(NAMESPACE.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", impossible);
        }
    }

    private FlowFailure invalid() {
        return new FlowFailure(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CURSOR);
    }
}
