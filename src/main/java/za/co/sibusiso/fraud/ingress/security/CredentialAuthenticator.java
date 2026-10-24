package za.co.sibusiso.fraud.ingress.security;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
@Profile("!migrate & !policy-admin")
public final class CredentialAuthenticator {
    private static final Pattern CREDENTIAL_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{1,64}\\.[a-f0-9]{64}");
    private static final int SHA_256_DIGEST_BYTES = 32;

    private final CredentialStore store;

    public CredentialAuthenticator(CredentialStore store) {
        this.store = store;
    }

    public Caller authenticate(String key) {
        if (key == null || !CREDENTIAL_PATTERN.matcher(key).matches()) {
            throw new ApiFailure(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIAL);
        }
        int separator = key.indexOf('.');
        var credential = store.active(key.substring(0, separator));
        byte[] expected =
                credential.isEmpty()
                        ? new byte[SHA_256_DIGEST_BYTES]
                        : HexFormat.of().parseHex(credential.orElseThrow().secretHash());
        boolean equal = MessageDigest.isEqual(expected, digest(key.substring(separator + 1)));
        if (!equal || credential.isEmpty()) {
            throw new ApiFailure(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIAL);
        }
        return credential.orElseThrow().caller();
    }

    public static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
