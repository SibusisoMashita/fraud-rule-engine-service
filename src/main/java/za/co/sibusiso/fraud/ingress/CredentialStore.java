package za.co.sibusiso.fraud.ingress;

import java.util.Optional;

public interface CredentialStore {
    record Credential(String secretHash, Caller caller) { }
    Optional<Credential> active(String keyId);
}
