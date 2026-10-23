package za.co.sibusiso.fraud.policy;

public final class PolicyUnavailableException extends IllegalStateException {
    public PolicyUnavailableException(Throwable cause) {
        super("Active business policy is unavailable or invalid", cause);
    }
}
