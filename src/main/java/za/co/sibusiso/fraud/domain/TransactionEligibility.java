package za.co.sibusiso.fraud.domain;

public enum TransactionEligibility {
    ELIGIBLE, UNSUPPORTED_CURRENCY, AMOUNT_SCALE, AMOUNT_LIMIT, EVENT_TOO_OLD, EVENT_IN_FUTURE
}
