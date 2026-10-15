package za.co.sibusiso.fraud.evaluation;

import za.co.sibusiso.fraud.domain.ContractChecks;

/** Aggregate requirements independent of any particular fraud rule. */
public sealed interface HistoryQuery permits HistoryQuery.Count, HistoryQuery.Spend {
    int windowSeconds();

    record Count(int windowSeconds) implements HistoryQuery {
        public Count { ContractChecks.require(windowSeconds > 0, "History window must be positive"); }
    }
    record Spend(int windowSeconds, String currency) implements HistoryQuery {
        public Spend {
            ContractChecks.require(windowSeconds > 0, "History window must be positive");
            currency = ContractChecks.currency(currency);
        }
    }
}
