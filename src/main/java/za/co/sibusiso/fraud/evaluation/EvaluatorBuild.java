package za.co.sibusiso.fraud.evaluation;

import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

import za.co.sibusiso.fraud.FraudApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
@Profile("!migrate & !policy-admin")
public final class EvaluatorBuild {
    private final String id;

    public EvaluatorBuild() throws IOException, NoSuchAlgorithmException {
        var source = new ApplicationHome(FraudApplication.class).getSource();
        if (source == null) {
            var location =
                    FraudApplication.class.getProtectionDomain().getCodeSource().getLocation();
            if (!location.getProtocol().equals(ResourceUtils.URL_PROTOCOL_FILE))
                throw new IllegalStateException("Cannot identify evaluator artifact");
            source = java.nio.file.Path.of(java.net.URI.create(location.toExternalForm())).toFile();
        }
        var root = source.toPath();
        var digest = MessageDigest.getInstance("SHA-256");
        if (Files.isRegularFile(root)) {
            try (var input = Files.newInputStream(root)) {
                input.transferTo(
                        new java.security.DigestOutputStream(
                                java.io.OutputStream.nullOutputStream(), digest));
            }
        } else {
            try (var files = Files.walk(root)) {
                for (var path : files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(
                            root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    try (var input = Files.newInputStream(path)) {
                        input.transferTo(
                                new java.security.DigestOutputStream(
                                        java.io.OutputStream.nullOutputStream(), digest));
                    }
                }
            }
        }
        id = "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    public String id() {
        return id;
    }
}
