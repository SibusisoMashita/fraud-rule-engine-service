package za.co.sibusiso.fraud.ingress.security;

import java.util.Optional;

public interface CredentialStore {
    record Credential(String secretHash, Caller caller) { }
    Optional<Credential> active(String keyId);
}
