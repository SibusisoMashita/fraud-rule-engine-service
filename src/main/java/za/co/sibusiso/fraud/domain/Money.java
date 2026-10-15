package za.co.sibusiso.fraud.domain;

import java.math.BigDecimal;

public record Money(String currency, BigDecimal amount) {
    public Money {
        currency = ContractChecks.currency(currency);
        amount = ContractChecks.amount(amount);
    }

    public static Money parse(String currency, String amount) {
        return new Money(currency, ContractChecks.amount(amount));
    }
}
