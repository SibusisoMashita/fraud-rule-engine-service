package za.co.sibusiso.fraud.evaluation;

import org.springframework.http.HttpStatus;
import za.co.sibusiso.fraud.contracts.ErrorCode;

public final class FlowFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean unknown;

    public FlowFailure(HttpStatus status, ErrorCode code) {
        this(status, code, false);
    }

    public FlowFailure(HttpStatus status, ErrorCode code, boolean unknown) {
        this(status, code.name(), unknown);
    }

    public FlowFailure(HttpStatus status, String code) {
        this(status, code, false);
    }

    public FlowFailure(HttpStatus status, String code, boolean unknown) {
        super(code);
        this.status = status;
        this.code = code;
        this.unknown = unknown;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean unknown() {
        return unknown;
    }
}
