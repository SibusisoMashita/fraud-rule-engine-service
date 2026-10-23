package za.co.sibusiso.fraud.domain;

import za.co.sibusiso.fraud.domain.TransactionValidationException.Field;
import za.co.sibusiso.fraud.domain.TransactionValidationException.Reason;

import java.math.BigDecimal;

public record Money(String currency, BigDecimal amount) {
    public Money {
        currency = validateCurrency(currency);
        amount = validateAmount(amount);
    }

    public static Money parse(String currency, String amount) {
        return new Money(
                currency,
                TransactionValidationException.validate(
                        Field.AMOUNT, Reason.INVALID_AMOUNT, () -> ContractChecks.amount(amount)));
    }

    private static String validateCurrency(String value) {
        return TransactionValidationException.validate(
                Field.CURRENCY, Reason.INVALID_CURRENCY, () -> ContractChecks.currency(value));
    }

    private static BigDecimal validateAmount(BigDecimal value) {
        return TransactionValidationException.validate(
                Field.AMOUNT, Reason.INVALID_AMOUNT, () -> ContractChecks.amount(value));
    }
}
