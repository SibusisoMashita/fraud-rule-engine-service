package za.co.sibusiso.fraud.evaluation;

public final class FlowFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean unknown;
    public FlowFailure(int status, String code) { this(status, code, false); }
    public FlowFailure(int status, String code, boolean unknown) {
        super(code); this.status = status; this.code = code; this.unknown = unknown;
    }
    public int status() { return status; }
    public String code() { return code; }
    public boolean unknown() { return unknown; }
}
